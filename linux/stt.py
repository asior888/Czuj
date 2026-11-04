import io
import wave

import numpy as np
import sounddevice as sd
from groq import Groq

import config

_client = None


def _get_client():
    global _client
    if _client is None:
        _client = Groq(api_key=config.GROQ_API_KEY)
    return _client


def record(seconds=config.RECORD_SECONDS):
    """Nagrywa z mikrofonu, zwraca bajty pliku WAV (mono, 16 kHz)."""
    audio = sd.rec(
        int(seconds * config.SAMPLE_RATE),
        samplerate=config.SAMPLE_RATE,
        channels=1,
        dtype="int16",
    )
    sd.wait()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(config.SAMPLE_RATE)
        w.writeframes(audio.tobytes())
    return buf.getvalue()


def transcribe(wav_bytes):
    """Wysyła WAV do Whispera na Groq, zwraca tekst."""
    result = _get_client().audio.transcriptions.create(
        file=("audio.wav", wav_bytes),
        model=config.STT_MODEL,
        language=config.LANGUAGE,
    )
    return result.text.strip()


def listen():
    """Nagraj i zamień na tekst."""
    return transcribe(record())


if __name__ == "__main__":
    print("Mów...")
    print("Usłyszałem:", listen())
