"""Provision the parking knowledge domain using the existing operation password."""
import argparse
import json
import urllib.request
from deploy_cockpit import read_action_auth

def post(url: str, body: dict, token: str = "") -> dict:
    headers = {"Content-Type": "application/json", "User-Agent": "ParkingAgentProvision/1.0"}
    if token: headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-base", default="https://caibinice.com/smartCockpit/api")
    args = parser.parse_args()
    base = args.api_base.rstrip("/")
    auth = post(base + "/action-auth/verify", {"password": read_action_auth()["password"]})
    result = post(base + "/parking-agent/knowledge/bootstrap", {}, auth["token"])
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__": main()
