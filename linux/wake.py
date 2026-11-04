"""Słowo wybudzające "Czuj" (opcjonalne, Vosk, działa offline).

Model polski: https://alphacephei.com/vosk/models -> vosk-model-small-pl-0.22
Rozpakuj do ścieżki z config.VOSK_MODEL_PATH.
"""
import json
import os

import config

_model = None


def _get_model():
    global _model
    if _model is None:
        from vosk import Model

        _model = Model(os.path.expanduser(config.VOSK_MODEL_PATH))
    return _model


def wait_for_wake(word=config.WAKE_WORD):
    """Blokuje, aż usłyszy słowo wybudzające. Potem zwalnia mikrofon."""
    import sounddevice as sd
    from vosk import KaldiRecognizer

    rec = KaldiRecognizer(_get_model(), 16000, json.dumps([word, "[unk]"]))
    with sd.RawInputStream(
        samplerate=16000, blocksize=4000, dtype="int16", channels=1
    ) as stream:
        while True:
            data, _overflow = stream.read(4000)
            if rec.AcceptWaveform(bytes(data)):
                heard = json.loads(rec.Result()).get("text", "")
            else:
                heard = json.loads(rec.PartialResult()).get("partial", "")
            if word in heard:
                return
