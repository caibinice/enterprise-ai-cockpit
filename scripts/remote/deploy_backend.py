"""Atomic backend-only release. Preserve the current frontend, environment and unit."""
from datetime import UTC, datetime
from hashlib import sha256
from pathlib import Path
import subprocess

from deploy_cockpit import find_jar
from remote_client import RemoteClient

ROOT = Path(__file__).resolve().parents[2]
jar = find_jar()
digest = sha256(jar.read_bytes()).hexdigest()
revision = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, text=True).strip()
release = f"{datetime.now(UTC):%Y%m%d%H%M%S}-{revision}-backend"
upload = f"/tmp/cockpit-{release}.jar"
script = f"/tmp/cockpit-{release}.sh"
wrapper = f"""#!/usr/bin/env bash
set -euo pipefail
root=/opt/enterprise-ai-cockpit
previous="$(readlink -f "$root/current")"
case "$previous" in "$root"/releases/*) ;; *) echo 'Unexpected current release'; exit 1;; esac
test -f "$previous/app.jar" && test -f "$previous/dist/index.html"
dest="$root/releases/{release}"
test ! -e "$dest"
backup="$root/backups/{release}"
mkdir -p "$backup" "$dest"
chmod 700 "$backup"
cp -p "$root/shared/app.env" "$backup/app.env"
cp -p /etc/systemd/system/enterprise-ai-cockpit.service "$backup/service"
env_before="$(sha256sum "$root/shared/app.env" /etc/systemd/system/enterprise-ai-cockpit.service)"
others_before="$(systemctl show nginx ai-quant-api ai-quant-worker crossborder-trend -p MainPID -p ActiveState)"
activated=false
rollback() {{
  if [[ "$activated" != true ]]; then return; fi
  ln -sfn "$previous" "$root/current.next"; mv -Tf "$root/current.next" "$root/current"
  ln -sfn "$previous/dist" "$root/www/smartCockpit.next"; mv -Tf "$root/www/smartCockpit.next" "$root/www/smartCockpit"
  systemctl restart enterprise-ai-cockpit.service || true
}}
trap rollback ERR
cp -a "$previous/." "$dest/"
echo '{digest}  {upload}' | sha256sum -c -
install -o aiapps -g aiapps -m 640 {upload} "$dest/app.jar"
chown -R aiapps:aiapps "$dest"
activated=true
ln -sfn "$dest" "$root/current.next"; mv -Tf "$root/current.next" "$root/current"
ln -sfn "$dest/dist" "$root/www/smartCockpit.next"; mv -Tf "$root/www/smartCockpit.next" "$root/www/smartCockpit"
systemctl restart enterprise-ai-cockpit.service
healthy=false
for _ in $(seq 1 75); do
  if curl -fsS http://127.0.0.1:8080/api/health >/dev/null; then healthy=true; break; fi
  sleep 2
done
test "$healthy" = true
test "$env_before" = "$(sha256sum "$root/shared/app.env" /etc/systemd/system/enterprise-ai-cockpit.service)"
test "$others_before" = "$(systemctl show nginx ai-quant-api ai-quant-worker crossborder-trend -p MainPID -p ActiveState)"
systemctl is-active --quiet enterprise-ai-cockpit.service
trap - ERR
rm -f {upload} {script}
echo 'BACKEND_RELEASE={release}'
echo "PREVIOUS_RELEASE=$previous"
echo "BACKUP=$backup"
echo 'FRONTEND_ENV_UNIT_AND_OTHER_SERVICES_PRESERVED=true'
"""
remote = RemoteClient()
try:
    remote.upload_file(jar, upload, 0o600)
    remote.upload_bytes(wrapper.encode(), script, 0o700)
    remote.run(f"/bin/bash {script}", root=True, timeout=600)
finally:
    remote.close()
