"""Wspólna mapa (serwer Cloudflare, bez kont). Wysyła TYLKO: losowy numer instalacji, typ trackera,
dzień (UTC), kratkę ok. 2 km.

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
import urllib.request
from datetime import datetime, timezone

import config

GRID = 0.02
STATE_FILE = os.path.join(config.CZUJ_DIR, "cloud.json")  # numer instalacji i lista wysłanych
_lock = threading.Lock()


def configured():
    return config.CLOUD_API.startswith("https://")


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


class HttpError(Exception):
    def __init__(self, code, body):
        super().__init__(f"HTTP {code}: {body[:160]}")
        self.code = code


def _http(url, method="GET", body=None, ctype="application/json"):
    data = body.encode() if isinstance(body, str) else body
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", ctype)
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


def _install_id(state):
    if len(state.get("dev", "")) != 32:
        state["dev"] = os.urandom(16).hex()
        _save_state(state)
    return state["dev"]


def report(alert_text):
    """Wysyła alarm, jeśli jest zgoda, adres serwera i pozycja. Najwyżej raz na typ i dzień."""
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
        body = {"dev": _install_id(state), "day": day, "type": typ, "cell": cell(*pos), "platform": "linux"}
        _http(f"{config.CLOUD_API}/v1/report", "POST", json.dumps(body))
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
    stats = _http(f"{config.CLOUD_API}/v1/stats")
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
            print("Serwer mapy nie jest ustawiony (CZUJ_API w ~/.czuj/env).")
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
