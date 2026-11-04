import asyncio
import os
import shutil
import subprocess
import tempfile

import edge_tts

import config

# Odtwarzacze po kolei; bierzemy pierwszy, który istnieje na urządzeniu
_PLAYERS = [
    ["ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet"],
    ["mpv", "--no-video", "--really-quiet"],
    ["cvlc", "--play-and-exit", "--quiet"],
    ["afplay"],
    ["termux-media-player", "play"],
]


async def _synthesize(text, path):
    await edge_tts.Communicate(text, config.TTS_VOICE).save(path)


def _find_player():
    for cmd in _PLAYERS:
        if shutil.which(cmd[0]):
            return cmd
    return None


def speak(text):
    """Zamienia tekst na mowę i odtwarza."""
    if not text:
        return
    player = _find_player()
    if player is None:
        print("[brak odtwarzacza] Czuj mówi:", text)
        return
    fd, path = tempfile.mkstemp(suffix=".mp3")
    os.close(fd)
    try:
        asyncio.run(_synthesize(text, path))
        subprocess.run(player + [path], check=False)
    finally:
        os.remove(path)


if __name__ == "__main__":
    speak("Cześć, tu Czuj. Pilnuję twoich pleców.")
