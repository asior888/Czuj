"""Agregator Czuja: surowe zgłoszenia -> publiczna mapa zbiorcza.

Ochrona osób śledzonych (publiczna mapa nie może namierzyć jednej osoby):
- kratka pokazana dopiero, gdy zgłosiły ją >= K różnych urządzeń,
- opóźnienie: tylko dni zakończone co najmniej 24 h temu (dziś i wczoraj pominięte),
- okno 7 dni; starsze surowe zgłoszenia są kasowane.
Uruchamia go GitHub Actions (konto serwisowe z sekretu FIREBASE_SA).
"""
import json
import os
import sys
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta, timezone

TYPES = ("airtag", "tile", "smarttag")
K = 3
WINDOW_DAYS = 7
DELAY_DAYS = 2
GRID = 0.02


def window(today):
    end = today - timedelta(days=DELAY_DAYS)
    start = end - timedelta(days=WINDOW_DAYS - 1)
    return start, end


def aggregate(reports, today):
    """reports: lista słowników {uid, day, cell, type}. Zwraca publiczną mapę."""
    start, end = window(today)
    counts = defaultdict(Counter)
    devices = defaultdict(set)
    for r in reports:
        try:
            d = date.fromisoformat(r["day"])
        except (KeyError, ValueError, TypeError):
            continue
        if not (start <= d <= end) or r.get("type") not in TYPES or not r.get("cell"):
            continue
        counts[r["cell"]][r["type"]] += 1
        devices[r["cell"]].add(r.get("uid"))
    cells = {}
    for cell, c in counts.items():
        if len(devices[cell]) >= K:
            cells[cell] = {"total": sum(c.values()), **{t: c[t] for t in TYPES}}
    return {
        "updated": today.isoformat(), "from": start.isoformat(), "to": end.isoformat(),
        "k": K, "grid": GRID, "cells": dict(sorted(cells.items())),
    }


def expired(reports, today):
    """Zgłoszenia starsze niż okno: do skasowania (minimalizacja danych)."""
    start, _ = window(today)
    out = []
    for r in reports:
        try:
            if date.fromisoformat(r["day"]) < start:
                out.append(r)
        except (KeyError, ValueError, TypeError):
            out.append(r)  # uszkodzony wpis też kasujemy
    return out


# ---------- Firestore (REST, konto serwisowe) ----------

def _session(sa):
    from google.auth.transport.requests import AuthorizedSession
    from google.oauth2 import service_account

    cred = service_account.Credentials.from_service_account_info(
        sa, scopes=["https://www.googleapis.com/auth/datastore"])
    return AuthorizedSession(cred)


def _val(v):
    return v.get("stringValue", v.get("integerValue"))


def run():
    sa = json.loads(os.environ["FIREBASE_SA"])
    base = (f"https://firestore.googleapis.com/v1/projects/{sa['project_id']}"
            "/databases/(default)/documents")
    s = _session(sa)
    reports, token = [], None
    while True:
        params = {"pageSize": 300}
        if token:
            params["pageToken"] = token
        r = s.get(f"{base}/reports", params=params, timeout=60)
        r.raise_for_status()
        body = r.json()
        for doc in body.get("documents", []):
            f = {k: _val(v) for k, v in doc.get("fields", {}).items()}
            f["_name"] = doc["name"]
            reports.append(f)
        token = body.get("nextPageToken")
        if not token:
            break
    today = datetime.now(timezone.utc).date()
    stats = aggregate(reports, today)
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    r = s.patch(f"{base}/stats/week", timeout=60, json={"fields": {
        "json": {"stringValue": json.dumps(stats, ensure_ascii=False)},
        "updated": {"timestampValue": now},
    }})
    r.raise_for_status()
    old = expired(reports, today)
    for rep in old:
        s.delete(f"https://firestore.googleapis.com/v1/{rep['_name']}", timeout=60).raise_for_status()
    summary = (f"{now} zgłoszeń: {len(reports)}, okno {stats['from']}..{stats['to']}, "
               f"kratek publicznych: {len(stats['cells'])}, skasowanych starych: {len(old)}")
    print(summary)
    return summary


if __name__ == "__main__":
    out = run()
    if len(sys.argv) > 1:
        with open(sys.argv[1], "w", encoding="utf-8") as f:
            f.write(out + "\n")
