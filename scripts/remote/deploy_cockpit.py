from __future__ import annotations

import configparser
import json
import subprocess
import sys
import tarfile
from datetime import UTC, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CODES_ROOT = ROOT.parent
BLOG_ROOT = CODES_ROOT / "ai-blog"
QUANT_ROOT = CODES_ROOT / "ai-quantum"
STATE_DIR = ROOT / ".deploy"

sys.path.insert(0, str(QUANT_ROOT / "scripts" / "remote"))
from remote_client import RemoteClient  # noqa: E402


def read_credentials() -> configparser.ConfigParser:
    path = ROOT / "credentials.txt"
    if not path.exists():
        raise RuntimeError(f"Missing ignored credentials file: {path}")
    parser = configparser.ConfigParser(interpolation=None)
    parser.read(path, encoding="utf-8")
    return parser


def read_action_auth() -> dict[str, str]:
    path = BLOG_ROOT / ".deploy" / "action-auth.json"
    if not path.exists():
        raise RuntimeError(f"Missing shared action auth file: {path}")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not value.get("password") or not value.get("tokenSecret"):
        raise RuntimeError("Shared action auth file is incomplete")
    return value


def git_revision() -> str:
    revision = subprocess.check_output(
        ["git", "rev-parse", "--short", "HEAD"],
        cwd=ROOT,
        text=True,
    ).strip()
    dirty = subprocess.check_output(
        ["git", "status", "--porcelain", "--untracked-files=no"],
        cwd=ROOT,
        text=True,
    ).strip()
    return f"{revision}-working" if dirty else revision


def find_jar() -> Path:
    candidates = [
        path
        for path in (ROOT / "backend" / "target").glob("*.jar")
        if not path.name.endswith(".jar.original")
    ]
    if len(candidates) != 1:
        raise RuntimeError("Expected one executable JAR in backend/target")
    return candidates[0]


def build_archive(destination: Path, jar: Path) -> None:
    dist = ROOT / "frontend" / "dist"
    mcp_servers = ROOT / "backend" / "mcp-servers"
    if not (dist / "index.html").exists():
        raise RuntimeError("Missing frontend build output")
    if not mcp_servers.exists():
        raise RuntimeError("Missing MCP server directory")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(destination, "w:gz") as bundle:
        for source in sorted(dist.rglob("*")):
            if source.is_file():
                bundle.add(
                    source,
                    arcname=(
                        Path("dist") / source.relative_to(dist)
                    ).as_posix(),
                )
        bundle.add(jar, arcname="app.jar")
        for source in sorted(mcp_servers.rglob("*")):
            if source.is_file():
                bundle.add(
                    source,
                    arcname=(
                        Path("mcp-servers") / source.relative_to(mcp_servers)
                    ).as_posix(),
                )


def systemd_env(values: dict[str, str]) -> bytes:
    lines = [
        f"{key}={json.dumps(str(value), ensure_ascii=False)}"
        for key, value in values.items()
    ]
    return ("\n".join(lines) + "\n").encode("utf-8")


def environment(
    credentials: configparser.ConfigParser,
    action_auth: dict[str, str],
) -> bytes:
    mysql = credentials["mysql.remote"]
    vector = credentials["postgresql.vector"]
    llm = credentials["deepseek.api"]
    values = {
        "SERVER_ADDRESS": "127.0.0.1",
        "SERVER_PORT": "8080",
        "APP_REPOSITORY_MODE": "mysql",
        "MYSQL_URL": (
            "jdbc:mysql://127.0.0.1:"
            f"{mysql.get('port', '3306')}/{mysql['database']}"
            "?useUnicode=true&characterEncoding=utf8"
            "&serverTimezone=Asia/Shanghai&useSSL=false"
            "&allowPublicKeyRetrieval=true"
        ),
        "MYSQL_USER": mysql["user"],
        "MYSQL_PASSWORD": mysql["password"],
        "DB_POOL_MAX_SIZE": "2",
        "DB_POOL_MIN_IDLE": "0",
        "QUARTZ_THREAD_COUNT": "2",
        "FLYWAY_ENABLED": "true",
        "FLYWAY_BASELINE_ON_MIGRATE": "true",
        "FLYWAY_BASELINE_VERSION": "0",
        "LLM_ENABLED": "true",
        "LLM_PROVIDER": "openai-compatible",
        "OPENAI_BASE_URL": llm.get("base-url", "https://api.deepseek.com"),
        "OPENAI_API_KEY": llm["api-key"],
        "DEEPSEEK_API_KEY": llm["api-key"],
        "LLM_MODEL": "deepseek-v4-flash",
        "VECTOR_ENABLED": "true",
        "VECTOR_DATABASE_URL": (
            "jdbc:postgresql://127.0.0.1:"
            f"{vector.get('port', '5432')}/{vector['database']}"
        ),
        "VECTOR_DATABASE_USER": vector["user"],
        "VECTOR_DATABASE_PASSWORD": vector["password"],
        "VECTOR_DATABASE_SCHEMA": "public",
        "EMBEDDING_MODE": "local",
        "EMBEDDING_DIMENSIONS": credentials.get(
            "embedding",
            "dimensions",
            fallback="1536",
        ),
        "MCP_ENABLED": "true",
        "MCP_NODE_COMMAND": "/usr/bin/node",
        "MCP_WEATHER_SERVER": "mcp-servers/weather-mcp-server.js",
        "MCP_REQUEST_TIMEOUT": "20s",
        "ACTION_PASSWORD": action_auth["password"],
        "ACTION_TOKEN_SECRET": action_auth["tokenSecret"],
        "ACTION_TOKEN_TTL_MINUTES": "30",
    }
    return systemd_env(values)


def render_service() -> bytes:
    template = (
        ROOT
        / "deploy"
        / "systemd"
        / "enterprise-ai-cockpit.service.template"
    ).read_text(encoding="utf-8")
    return template.replace("__APP_USER__", "aiapps").encode("utf-8")


def main() -> None:
    credentials = read_credentials()
    action_auth = read_action_auth()
    release = f"{datetime.now(UTC):%Y%m%d%H%M%S}-{git_revision()}"
    archive = STATE_DIR / f"cockpit-{release}.tar.gz"
    remote_archive = f"/tmp/{archive.name}"
    build_archive(archive, find_jar())

    remote = RemoteClient()
    try:
        remote.upload_file(archive, remote_archive, 0o600)
        remote.upload_bytes(
            environment(credentials, action_auth),
            "/tmp/cockpit-app.env",
            0o600,
        )
        remote.upload_bytes(
            render_service(),
            "/tmp/enterprise-ai-cockpit.service",
            0o644,
        )
        wrapper = f"""#!/usr/bin/env bash
set -euo pipefail

root=/opt/enterprise-ai-cockpit
release={release}
archive={remote_archive}
previous="$(readlink -f "$root/current" 2>/dev/null || true)"
before_nginx="$(systemctl is-active nginx || true)"
before_quant="$(systemctl is-active ai-quant-api || true)"
before_cross="$(systemctl is-active crossborder-trend || true)"

if ! command -v node >/dev/null 2>&1; then
  dnf -y --disablerepo='epel*' install nodejs >/dev/null
fi
test -x /usr/bin/node
id aiapps >/dev/null 2>&1

mkdir -p "$root/releases/$release" "$root/shared" "$root/www"
tar -xzf "$archive" -C "$root/releases/$release"
chown -R aiapps:aiapps "$root/releases/$release" "$root/shared"
chmod 755 "$root" "$root/releases" "$root/www" "$root/releases/$release"

ln -sfn "$root/releases/$release" "$root/current.next"
mv -Tf "$root/current.next" "$root/current"
ln -sfn "$root/releases/$release/dist" "$root/www/smartCockpit.next"
mv -Tf "$root/www/smartCockpit.next" "$root/www/smartCockpit"

install -o aiapps -g aiapps -m 600 \
  /tmp/cockpit-app.env "$root/shared/app.env"
install -m 644 /tmp/enterprise-ai-cockpit.service \
  /etc/systemd/system/enterprise-ai-cockpit.service
systemctl daemon-reload
systemctl enable enterprise-ai-cockpit.service >/dev/null
systemctl restart enterprise-ai-cockpit.service

healthy=false
for _attempt in $(seq 1 75); do
  if curl -fsS http://127.0.0.1:8080/api/health >/dev/null; then
    healthy=true
    break
  fi
  sleep 2
done

if [[ "$healthy" != true ]]; then
  if [[ -n "$previous" && -d "$previous" ]]; then
    ln -sfn "$previous" "$root/current.next"
    mv -Tf "$root/current.next" "$root/current"
    ln -sfn "$previous/dist" "$root/www/smartCockpit.next"
    mv -Tf "$root/www/smartCockpit.next" "$root/www/smartCockpit"
    systemctl restart enterprise-ai-cockpit.service || true
  fi
  journalctl -u enterprise-ai-cockpit.service -n 80 --no-pager
  exit 1
fi

systemctl is-active --quiet enterprise-ai-cockpit.service
after_nginx="$(systemctl is-active nginx || true)"
after_quant="$(systemctl is-active ai-quant-api || true)"
after_cross="$(systemctl is-active crossborder-trend || true)"
test "$before_nginx" = "$after_nginx"
test "$before_quant" = "$after_quant"
test "$before_cross" = "$after_cross"

find "$root/releases" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\\n' \
  | sort -nr | tail -n +6 | cut -d' ' -f2- | xargs -r rm -rf
rm -f "$archive" /tmp/cockpit-app.env \
  /tmp/enterprise-ai-cockpit.service /tmp/deploy-enterprise-ai-cockpit.sh

printf 'COCKPIT_SERVICE=%s\\n' "$(systemctl is-active enterprise-ai-cockpit.service)"
printf 'UNCHANGED_SERVICES=nginx:%s,quant:%s,crossborder:%s\\n' \
  "$after_nginx" "$after_quant" "$after_cross"
"""
        remote.upload_bytes(
            wrapper.encode("utf-8"),
            "/tmp/deploy-enterprise-ai-cockpit.sh",
            0o700,
        )
        remote.run(
            "/bin/bash /tmp/deploy-enterprise-ai-cockpit.sh",
            root=True,
            timeout=900,
        )
    finally:
        remote.close()
        archive.unlink(missing_ok=True)

    print("PUBLIC_URL=https://101.132.78.217/smartCockpit/")
    print(f"COCKPIT_RELEASE={release}")


if __name__ == "__main__":
    main()
