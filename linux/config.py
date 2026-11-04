import os

# Klucze: zmienne środowiskowe albo plik ~/.czuj/env (linie KLUCZ=wartość).
# Nigdy nie wpisuj kluczy do repo.
CZUJ_DIR = os.path.expanduser("~/.czuj")


def _load_env_file(path=os.path.join(CZUJ_DIR, "env")):
    try:
        if os.stat(path).st_mode & 0o077:
            print(f"[UWAGA] {path} mogą czytać inni użytkownicy. Popraw: chmod 600 {path}")
    except OSError:
        return
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, value = line.partition("=")
                os.environ.setdefault(key.strip(), value.strip().strip('"'))
    except OSError:
        pass


_load_env_file()

ANTHROPIC_API_KEY = os.environ.get("ANTHROPIC_API_KEY", "")
GROQ_API_KEY = os.environ.get("GROQ_API_KEY", "")

# Model do rozmowy
LLM_MODEL = "claude-sonnet-5-5"
LLM_MAX_TOKENS = 300  # krótkie odpowiedzi = szybciej mówione

# Rozpoznawanie mowy
STT_MODEL = "whisper-large-v3-turbo"
LANGUAGE = "pl"

# Głos
TTS_VOICE = "pl-PL-MarekNeural"

# Audio
SAMPLE_RATE = 16000
RECORD_SECONDS = 6

# Skaner i słowo wybudzające
SCAN_INTERVAL = 60  # sekundy między skanami
WAKE_WORD = "czuj"
VOSK_MODEL_PATH = "~/.czuj/vosk-model-small-pl-0.22"

# Wspólna mapa (Firebase). Wartości publiczne z założenia, dostęp chronią reguły.
# Puste = funkcja wyłączona. Zgoda na wysyłanie: CZUJ_SHARE=1 w ~/.czuj/env.
CLOUD_PROJECT = ""
CLOUD_API_KEY = ""

# Osobowość asystenta
SYSTEM_PROMPT = (
    "Jesteś Czuj, osobisty strażnik w kieszeni. Pilnujesz, czy za użytkownikiem "
    "nie jeździ nieznany tracker Bluetooth (AirTag, Tile, SmartTag). "
    "Odpowiadaj po polsku, maksymalnie 2 krótkie zdania, bez list i formatowania. "
    "Nie wymyślaj wykryć: o trackerach mów tylko na podstawie stanu skanera. "
    "Jeśli ktoś czuje się zagrożony, poradź zadzwonić pod 112."
)
