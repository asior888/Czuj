import os

# Klucze trzymamy w zmiennych środowiskowych, nie w kodzie
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

# Osobowość asystenta (uzupełnimy z głosówek)
SYSTEM_PROMPT = (
    "Jesteś Czuj, osobisty strażnik w kieszeni. Odpowiadaj po polsku, "
    "maksymalnie 2 krótkie zdania, bez list i formatowania."
)
