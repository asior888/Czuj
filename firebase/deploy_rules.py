"""Wdraża firestore.rules przez Firebase Rules API (konto serwisowe z FIREBASE_SA)."""
import json
import os
import sys

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

sa = json.loads(os.environ["FIREBASE_SA"])
project = sa["project_id"]
cred = service_account.Credentials.from_service_account_info(
    sa, scopes=["https://www.googleapis.com/auth/cloud-platform"])
s = AuthorizedSession(cred)
api = f"https://firebaserules.googleapis.com/v1/projects/{project}"
src = open(os.path.join(os.path.dirname(__file__), "firestore.rules"), encoding="utf-8").read()

r = s.post(f"{api}/rulesets", json={"source": {"files": [{"name": "firestore.rules", "content": src}]}}, timeout=60)
if r.status_code != 200:
    print("Reguły odrzucone:", r.status_code, r.text[:2000])
    sys.exit(1)
ruleset = r.json()["name"]
release = f"projects/{project}/releases/cloud.firestore"
r = s.patch(f"https://firebaserules.googleapis.com/v1/{release}",
            json={"release": {"name": release, "rulesetName": ruleset}}, timeout=60)
if r.status_code == 404:
    r = s.post(f"{api}/releases", json={"name": release, "rulesetName": ruleset}, timeout=60)
if r.status_code != 200:
    print("Nie udało się włączyć reguł:", r.status_code, r.text[:2000])
    sys.exit(1)
print("Reguły wdrożone:", ruleset)
