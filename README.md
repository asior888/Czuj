# Czuj

Osobisty strażnik w kieszeni. Asystent głosowy, który pilnuje,
czy coś cię nie śledzi: wykrywa nieznane nadajniki Bluetooth,
które podróżują razem z tobą, i ostrzega głosem.

## Jak działa
mikrofon -> STT -> mózg (LLM) -> TTS -> głośnik
Skaner Bluetooth działa w tle i sam się odzywa przy podejrzanym sygnale.

## Instalacja
pip install -r requirements.txt

## Klucze (zmienne środowiskowe, nigdy w repo)
export ANTHROPIC_API_KEY="..."
export GROQ_API_KEY="..."

## Test
python stt.py   # test mikrofonu i rozpoznawania
python tts.py   # test głosu

## Pliki
config.py, stt.py, tts.py, brain.py, scanner.py, alerts.py, main.py

## Status
W budowie.
