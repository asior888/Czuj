"""Wspólna baza (Firebase REST, bez SDK). Wysyła TYLKO typ trackera, dzień (UTC), kratkę ok. 2 km.

Linux nie ma GPS: kratkę bierzemy z ustawień (CZUJ_LAT, CZUJ_LON w ~/.czuj/env), więc
nadaje się do urządzenia stojącego w jednym miejscu (dom, auto z modułem). Bez tych ustawień
albo bez zgody (CZUJ_SHARE=1) nic nie jest wysyłane.

Użycie:  python cloud.py okolica     (mapa zbiorcza w promieniu ok. 3 km)
"""
import json
import math
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

import config

GRID = 0.02
STATE_FILE = os.path.join(config.CZUJ_DIR, "cloud.json")
_lock = threading.Lock()
_token = {"id": "", "uid": "", "until": 0.0}


def configured():
    return bool(config.CLOUD_PROJECT and config.CLOUD_API_KEY)


def sharing():
    return os.environ.get("CZUJ_SHARE") == "1"


def my_position():
    try:
        return float(os.environ["CZUJ_LAT"]), float(os.environ["CZUJ_LON"])
    except (KeyError, ValueError):
        return None


def type_of(text):
    if "AirTag" in text:
        return "airtag"
    if "SmartTag" in text:
        return "smarttag"
    if "Tile" in text:
        return "tile"
    return None


def cell(lat, lon):
    """Zaokrąglenie jak Math.round w Kotlinie (floor(x + 0.5)): kratki identyczne na każdej platformie."""
    def r(v):
        return math.floor(v / GRID + 0.5) * GRID
    return f"{r(lat):.2f}:{r(lon):.2f}".replace("-0.00", "0.00")


def _docs():
    return (f"https://firestore.googleapis.com/v1/projects/{config.CLOUD_PROJECT}"
            "/databases/(default)/documents")


class HttpError(Exception):
    def __init__(self, code, body):
        super().__init__(f"HTTP {code}: {body[:160]}")
        self.code = code


def _http(url, method="GET", body=None, ctype="application/json", bearer=None):
    data = body.encode() if isinstance(body, str) else body
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", ctype)
    if bearer:
        req.add_header("Authorization", "Bearer " + bearer)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            txt = r.read().decode()
    except urllib.error.HTTPError as e:
        raise HttpError(e.code, e.read().decode(errors="replace")) from None
    return json.loads(txt) if txt.strip() else {}


def _load_state():
    try:
        with open(STATE_FILE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def _save_state(state):
    os.makedirs(config.CZUJ_DIR, mode=0o700, exist_ok=True)
    fd = os.open(STATE_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)  # token logowania: tylko właściciel
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(state, f)


def _auth(state):
    if _token["id"] and time.time() < _token["until"]:
        return _token["id"], _token["uid"]
    key = config.CLOUD_API_KEY
    j = None
    if state.get("refresh"):
        try:
            r = _http(f"https://securetoken.googleapis.com/v1/token?key={key}", "POST",
                      urllib.parse.urlencode({"grant_type": "refresh_token", "refresh_token": state["refresh"]}),
                      "application/x-www-form-urlencoded")
            j = {"idToken": r["id_token"], "refreshToken": r["refresh_token"],
                 "localId": r["user_id"], "expiresIn": r.get("expires_in", "3600")}
        except HttpError as e:
            if e.code != 400:  # błąd sieci nie tworzy nowego konta
                raise
    if j is None:
        j = _http(f"https://identitytoolkit.googleapis.com/v1/accounts:signUp?key={key}", "POST",
                  json.dumps({"returnSecureToken": True}))
    _token.update(id=j["idToken"], uid=j["localId"], until=time.time() + int(j.get("expiresIn", 3600)) - 300)
    state["refresh"] = j["refreshToken"]
    _save_state(state)
    return _token["id"], _token["uid"]


def report(alert_text):
    """Wysyła alarm, jeśli jest zgoda, konfiguracja i pozycja. Najwyżej raz na typ i dzień."""
    pos = my_position()
    typ = type_of(alert_text)
    if not (configured() and sharing() and pos and typ):
        return False
    with _lock:
        state = _load_state()
        day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        key = f"{day}_{typ}"
        sent = state.get("sent", [])
        if key in sent:
            return False
        token, uid = _auth(state)
        fields = {"uid": {"stringValue": uid}, "day": {"stringValue": day},
                  "cell": {"stringValue": cell(*pos)}, "type": {"stringValue": typ},
                  "platform": {"stringValue": "linux"}, "v": {"integerValue": "1"}}
        try:
            _http(f"{_docs()}/reports?documentId={uid}_{key}", "POST", json.dumps({"fields": fields}), bearer=token)
        except HttpError as e:
            if e.code != 409:
                raise
        state["sent"] = (sent + [key])[-30:]
        _save_state(state)
        return True


def report_async(alert_text):
    def run():
        try:
            report(alert_text)
        except Exception as e:
            print("[mapa] wysyłka nieudana:", e)
    threading.Thread(target=run, daemon=True).start()


def nearby(lat, lon):
    """Mapa zbiorcza w promieniu ok. 3 km (3 x 3 kratki). None = brak danych."""
    if not configured():
        return None
    try:
        doc = _http(f"{_docs()}/stats/week?key={config.CLOUD_API_KEY}")
    except HttpError as e:
        if e.code == 404:
            return None
        raise
    stats = json.loads(doc["fields"]["json"]["stringValue"])
    cells = stats.get("cells", {})
    out = {"total": 0, "airtag": 0, "tile": 0, "smarttag": 0, "from": stats.get("from"), "to": stats.get("to")}
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            c = cells.get(cell(lat + dy * GRID, lon + dx * GRID))
            if c:
                for k in ("total", "airtag", "tile", "smarttag"):
                    out[k] += c.get(k, 0)
    return out


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "okolica":
        pos = my_position()
        if not configured():
            print("Wspólna baza nie jest skonfigurowana (config.py: CLOUD_PROJECT, CLOUD_API_KEY).")
        elif not pos:
            print("Ustaw CZUJ_LAT i CZUJ_LON w ~/.czuj/env.")
        else:
            n = nearby(*pos)
            if n is None:
                print("Mapa jeszcze pusta (liczy się raz na dobę).")
            else:
                print(f"W promieniu ok. 3 km ({n['from']} – {n['to']}): {n['total']} alarmów "
                      f"(AirTag {n['airtag']}, Tile {n['tile']}, SmartTag {n['smarttag']}).")
    else:
        print(__doc__)
