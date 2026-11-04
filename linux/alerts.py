"""Alarmy i mówienie: jedna kolejka, żeby głosy nie nakładały się na siebie."""
import os
import queue
import threading
from datetime import datetime

LOG_FILE = os.path.expanduser("~/.czuj/alerts.log")
KEEP = 50  # tyle ostatnich alarmów pamiętamy w pamięci


class Alerts:
    def __init__(self, speak=None, logfile=LOG_FILE):
        self._speak = speak
        self._logfile = logfile
        self._queue = queue.Queue()
        self.history = []
        threading.Thread(target=self._worker, daemon=True).start()

    def push(self, text):
        """Alarm: do dziennika, na ekran i na głos."""
        line = f"{datetime.now():%Y-%m-%d %H:%M:%S} {text}"
        self.history.append(line)
        del self.history[:-KEEP]
        print("[ALARM]", text)
        try:
            os.makedirs(os.path.dirname(self._logfile) or ".", exist_ok=True)
            with open(self._logfile, "a", encoding="utf-8") as f:
                f.write(line + "\n")
        except OSError:
            pass
        self._queue.put(text)

    def say(self, text):
        """Zwykła wypowiedź (odpowiedź asystenta)."""
        self._queue.put(text)

    def recent(self, n=3):
        return self.history[-n:]

    def _worker(self):
        while True:
            text = self._queue.get()
            if self._speak is None:
                continue
            try:
                self._speak(text)
            except Exception as e:
                print("[głos] błąd:", e)
