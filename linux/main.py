"""Czuj: osobisty strażnik w kieszeni (wersja Linux).

Uruchom:  python main.py          (--caps pokazuje tylko, co działa na tym urządzeniu)
"""
import argparse
import asyncio
import sys
import threading
import time

import caps
import config
from alerts import Alerts


def status_line(status, alerts):
    """Jedno zdanie o stanie skanera, dla modelu."""
    if not status:
        return "skaner wyłączony na tym urządzeniu"
    ago = int(time.time() - status["last"]) if "last" in status else None
    parts = [
        "ostatni skan " + (f"{ago} s temu" if ago is not None else "jeszcze nie był"),
        f"trackerów w zasięgu: {status.get('trackers', 0)}",
    ]
    recent = alerts.recent(3)
    parts.append("ostatnie alarmy: " + ("; ".join(recent) if recent else "brak"))
    return ", ".join(parts)


def start_scanner(alerts, status, interval):
    import scanner

    def run():
        asyncio.run(scanner.watch(alerts.push, interval, status))

    threading.Thread(target=run, daemon=True).start()


def conversation(c, alerts, status):
    use_voice = c["mic"][0] and c["stt"][0]
    use_llm = c["llm"][0]
    use_wake = use_voice and c["wake"][0]
    interactive = sys.stdin.isatty()

    # bez wake word i bez klawiatury nie ma jak zacząć rozmowy
    if use_voice and not use_wake and not interactive:
        use_voice = False
    if not use_voice and not interactive:
        print("Rozmowa niedostępna (brak wake word i terminala). Działa tylko skaner.")
        threading.Event().wait()
        return

    if use_voice:
        import stt
    if use_llm:
        import brain
    if use_wake:
        import wake

    while True:
        try:
            if use_voice:
                if use_wake:
                    wake.wait_for_wake()
                    alerts.say("Słucham.")
                else:
                    input("Naciśnij Enter i mów... ")
                text = stt.listen()
            else:
                text = input("Ty: ")
            text = text.strip()
            if not text:
                continue
            if not use_llm:
                print("Usłyszałem:", text)
                continue
            answer = brain.ask(text, status_line(status, alerts))
            print("Czuj:", answer)
            alerts.say(answer)
        except (KeyboardInterrupt, EOFError):
            raise
        except Exception as e:
            print("[błąd rozmowy]", e)
            time.sleep(1)


def main():
    ap = argparse.ArgumentParser(description="Czuj, strażnik w kieszeni")
    ap.add_argument("--caps", action="store_true", help="pokaż możliwości i wyjdź")
    ap.add_argument("--no-scan", action="store_true", help="bez skanera Bluetooth")
    ap.add_argument("--interval", type=int, default=config.SCAN_INTERVAL,
                    help="co ile sekund skan (domyślnie %(default)s)")
    args = ap.parse_args()

    c = caps.detect()
    caps.show(c)
    if args.caps:
        return

    speak = None
    if c["speaker"][0]:
        import tts

        speak = tts.speak
    alerts = Alerts(speak)

    status = {}
    if c["scan"][0] and not args.no_scan:
        start_scanner(alerts, status, args.interval)
        alerts.say("Czuj pilnuje. Skaner włączony.")
    else:
        status = None  # status_line powie modelowi, że skanera nie ma

    try:
        conversation(c, alerts, status)
    except (KeyboardInterrupt, EOFError):
        print("\nKoniec.")


if __name__ == "__main__":
    main()
