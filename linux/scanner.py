"""Skaner trackerów Bluetooth (AirTag/Find My, Tile, SmartTag).

Reguły przeniesione z RfCore (VEL): tracker jest podejrzany, gdy
- widziany >= 4 razy,
- jest z nami >= 25 min,
- w >= 2 różnych miejscach (po >= 2 obserwacje w każdym).
To samo ostrzeżenie najwyżej raz na 6 h.
"""
import asyncio
import shutil
import subprocess
import time

from bleak import BleakScanner

MIN_SIGHTINGS = 4
TRACK_SECONDS = 25 * 60
MIN_PLACES = 2
MIN_PER_PLACE = 2
REPEAT_SECONDS = 6 * 3600
PLACE_JACCARD = 0.34
MAX_PLACES = 80


def classify(adv):
    """Zwraca nazwę trackera albo '' (nie tracker)."""
    apple = adv.manufacturer_data.get(0x004C)
    if apple and len(apple) >= 2 and apple[0] == 0x12 and apple[1] == 0x19:
        return "AirTag / Find My"
    for uuid in adv.service_uuids:
        u = uuid.lower()
        if u.startswith("0000feed") or u.startswith("0000feec"):
            return "Tile"
        if u.startswith("0000fd5a"):
            return "SmartTag (Samsung)"
    return ""


def wifi_top6():
    """6 najsilniejszych punktów Wi-Fi = odcisk miejsca (Linux, nmcli).
    Brak nmcli lub Wi-Fi -> pusty zbiór (miejsce nieznane)."""
    if not shutil.which("nmcli"):
        return frozenset()
    try:
        out = subprocess.run(
            ["nmcli", "-t", "-f", "BSSID,SIGNAL", "dev", "wifi", "list"],
            capture_output=True, text=True, timeout=15,
        ).stdout
    except Exception:
        return frozenset()
    rows = []
    for line in out.splitlines():
        # BSSID ma dwukropki zapisane jako \: w trybie -t
        bssid, _, sig = line.rpartition(":")
        if bssid and sig.isdigit():
            rows.append((int(sig), bssid.replace("\\", "")))
    rows.sort(reverse=True)
    return frozenset(b for _, b in rows[:6])


class TrackerWatch:
    def __init__(self):
        self.places = []      # lista zbiorów BSSID
        self.seen = {}        # adres -> dane trackera
        self.alerted = {}     # adres -> kiedy ostatnio ostrzegliśmy

    def place_of(self, top):
        if not top:
            return -1
        for i, p in enumerate(self.places):
            union = len(top | p)
            if union and len(top & p) / union >= PLACE_JACCARD:
                return i
        if len(self.places) >= MAX_PLACES:
            return -1
        self.places.append(set(top))
        return len(self.places) - 1

    def ingest(self, sightings, top, now=None):
        """sightings: lista (adres, nazwa_trackera, rssi). Zwraca listę alarmów."""
        now = now if now is not None else time.time()
        place = self.place_of(top)
        alerts = []
        for addr, tag, rssi in sightings:
            rec = self.seen.setdefault(
                addr, {"tag": tag, "n": 0, "first": now, "last": now, "places": {}}
            )
            rec["n"] += 1
            rec["last"] = now
            rec["places"][place] = rec["places"].get(place, 0) + 1
            span = rec["last"] - rec["first"]
            multi = sum(1 for p, c in rec["places"].items() if p >= 0 and c >= MIN_PER_PLACE)
            if (rec["n"] >= MIN_SIGHTINGS and span >= TRACK_SECONDS
                    and multi >= MIN_PLACES):
                last = self.alerted.get(addr)
                if last is None or now - last >= REPEAT_SECONDS:
                    self.alerted[addr] = now
                    alerts.append(
                        f"Uwaga: {rec['tag']} jest przy tobie od {int(span // 60)} minut, "
                        f"w {multi} różnych miejscach. Jeśli to nie twoje, sprawdź torbę, "
                        f"kurtkę i pojazd."
                    )
        return alerts


async def scan_once(seconds=8):
    """Jeden skan BLE; zwraca tylko trackery."""
    found = await BleakScanner.discover(timeout=seconds, return_adv=True)
    out = []
    for addr, (_dev, adv) in found.items():
        tag = classify(adv)
        if tag:
            out.append((addr, tag, adv.rssi))
    return out


async def watch(on_alert, interval=60, status=None):
    """Pętla w tle: skan co `interval` s, alarmy idą do on_alert(tekst)."""
    tw = TrackerWatch()
    while True:
        try:
            sightings = await scan_once()
            if status is not None:
                status.update(last=time.time(), trackers=len(sightings))
            top = await asyncio.to_thread(wifi_top6)
            for text in tw.ingest(sightings, top):
                on_alert(text)
        except Exception as e:  # skaner nie może wywalić całego asystenta
            print("[skaner] błąd:", e)
        await asyncio.sleep(interval)


if __name__ == "__main__":
    print("Skanuję 8 s...")
    for addr, tag, rssi in asyncio.run(scan_once()):
        print(addr, tag, rssi, "dBm")
    print("Koniec skanu.")
