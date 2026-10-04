"""Explicit, version-checked graph migration; never run implicitly at app startup."""
import argparse
from copy import deepcopy
import json
from pathlib import Path
import urllib.request

from deploy_cockpit import read_action_auth

ROOT = Path(__file__).resolve().parents[2]


def request(base, path, token="", body=None, method=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, headers=headers, method=method,
                                 data=json.dumps(body).encode() if body is not None else None)
    with urllib.request.urlopen(req, timeout=180) as response:
        return json.load(response)


def edge_key(edge):
    return tuple(sorted((edge["from"], edge["to"])))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-base", default="https://caibinice.com/smartCockpit/api/parking")
    parser.add_argument("--backup", type=Path, required=True)
    parser.add_argument("--expected-version", type=int)
    parser.add_argument("--apply", action="store_true", help="Default is a read-only preview")
    parser.add_argument("--setup", action="store_true", help="Import missing guides after applying")
    args = parser.parse_args()
    if args.setup and not args.apply:
        parser.error("--setup requires --apply")
    base = args.api_base.rstrip("/")
    login = request(base, "/login", body={"username": "admin", "password": read_action_auth()["password"]})
    token = login["token"]
    current = request(base, "/catalog", token)["graph"]
    if args.expected_version is not None and current["version"] != args.expected_version:
        raise ValueError("Graph version changed; inspect it again before migration")
    target = json.loads((ROOT / "backend/src/main/resources/parking/campus-layout.json").read_text(encoding="utf-8"))
    target = deepcopy(target)
    old_nodes = {n["id"]: n for n in current["nodes"]}
    target_ids = {n["id"] for n in target["nodes"]}
    if old_nodes.keys() - target_ids:
        raise ValueError("Custom nodes need an explicit merge; no update was sent")
    new_edges = {edge_key(e): e for e in target["edges"]}
    for edge in current["edges"]:
        key = edge_key(edge)
        if edge["closed"] and key not in new_edges:
            raise ValueError("A closed custom road needs an explicit merge; no update was sent")
        if key in new_edges:
            new_edges[key]["closed"] = edge["closed"]
    for node in target["nodes"]:
        if node["id"] in old_nodes:
            for field in ("closed", "accessible", "charging"):
                node[field] = old_nodes[node["id"]][field]
    changed = current["nodes"] != target["nodes"] or current["edges"] != target["edges"]
    result = {"apply": args.apply, "changed": changed, "beforeVersion": current["version"],
              "nodes": len(target["nodes"]), "edges": len(target["edges"])}
    if args.apply and changed:
        if args.backup.exists():
            if json.loads(args.backup.read_text(encoding="utf-8")) != current:
                raise ValueError("Existing backup differs from current graph; choose a new backup path")
        else:
            args.backup.parent.mkdir(parents=True, exist_ok=True)
            with args.backup.open("x", encoding="utf-8") as file:
                json.dump(current, file, ensure_ascii=False, indent=2)
                file.write("\n")
        target["version"] = current["version"]
        saved = request(base, "/graph", token, target, "PUT")
        assert saved["nodes"] == target["nodes"] and saved["edges"] == target["edges"]
        result["afterVersion"] = saved["version"]
        result["backup"] = str(args.backup.resolve())
    else:
        result["afterVersion"] = current["version"]
    if args.setup:
        result["setup"] = request(base, "/setup", token, {}, "POST")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
