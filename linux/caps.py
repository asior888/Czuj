"""Wykrywanie możliwości urządzenia.

Każda funkcja Czuja jest albo dostępna, albo wyłączona z podaniem powodu.
Brak funkcji nie wywala programu: reszta działa dalej.
"""
import importlib.util
import os
import shutil

import config

NAMES = {
    "mic": "mikrofon",
    "stt": "rozpoznawanie mowy",
    "llm": "asystent (rozmowa)",
    "speaker": "głos (odtwarzanie)",
    "scan": "skaner trackerów",
    "wake": 'słowo wybudzające "Czuj"',
    "cloud": "wspólna mapa (wysyłka)",
}


def _has(module):
    return importlib.util.find_spec(module) is not None


def _mic():
    if not _has("sounddevice"):
        return False, "brak biblioteki sounddevice"
    try:
        import sounddevice as sd

        sd.query_devices(kind="input")
        return True, ""
    except Exception:
        return False, "brak mikrofonu lub PortAudio (sudo apt install libportaudio2)"


def _stt():
    if not _has("groq"):
        return False, "brak biblioteki groq"
    if not config.GROQ_API_KEY:
        return False, "brak klucza GROQ_API_KEY"
    return True, ""


def _llm():
    if not _has("anthropic"):
        return False, "brak biblioteki anthropic"
    if not config.ANTHROPIC_API_KEY:
        return False, "brak klucza ANTHROPIC_API_KEY"
    return True, ""


def _speaker():
    if not _has("edge_tts"):
        return False, "brak biblioteki edge-tts"
    import tts

    if tts._find_player() is None:
        return False, "brak odtwarzacza (sudo apt install ffmpeg)"
    return True, ""


def _scan():
    if not _has("bleak"):
        return False, "brak biblioteki bleak"
    if not shutil.which("nmcli"):
        # bez odcisku miejsca nie da się odróżnić "tracker jedzie ze mną"
        # od "sąsiad ma AirTaga w domu", więc alarmy byłyby fałszywe
        return False, "brak nmcli (NetworkManager): nie da się rozpoznać zmiany miejsca"
    return True, ""


def _wake():
    if not _has("vosk"):
        return False, "brak biblioteki vosk (opcjonalna)"
    if not os.path.isdir(os.path.expanduser(config.VOSK_MODEL_PATH)):
        return False, f"brak modelu Vosk w {config.VOSK_MODEL_PATH}"
    return True, ""


def _cloud():
    import cloud

    if not cloud.configured():
        return False, "serwer nie ustawiony (CZUJ_API w ~/.czuj/env)"
    if not cloud.sharing():
        return False, "brak zgody (CZUJ_SHARE=1 w ~/.czuj/env)"
    if not cloud.my_position():
        return False, "brak pozycji (CZUJ_LAT, CZUJ_LON w ~/.czuj/env)"
    return True, ""


def detect():
    return {
        "mic": _mic(),
        "stt": _stt(),
        "llm": _llm(),
        "speaker": _speaker(),
        "scan": _scan(),
        "wake": _wake(),
        "cloud": _cloud(),
    }


def show(caps):
    print("Możliwości tego urządzenia:")
    for key, (ok, why) in caps.items():
        mark = "TAK" if ok else "NIE"
        extra = f" ({why})" if why else ""
        print(f"  [{mark}] {NAMES[key]}{extra}")
