#!/usr/bin/env bash
# Instalacja Czuja na Linuksie (Raspberry Pi OS, Debian, Ubuntu).
# Użycie:  ./install.sh            (podstawa)
#          ./install.sh --wake     (+ słowo wybudzające "Czuj", ok. 50 MB)
#          ./install.sh --service  (+ start razem z systemem)
set -e
cd "$(dirname "$0")"
DIR="$(pwd)"
CZUJ="$HOME/.czuj"
mkdir -p "$CZUJ"
chmod 700 "$CZUJ"   # klucze i dziennik alarmów tylko dla ciebie

echo "== Pakiety systemowe"
sudo apt-get update -q
sudo apt-get install -y -q python3-venv python3-pip libportaudio2 ffmpeg bluez unzip curl

if ! command -v nmcli >/dev/null; then
  echo "UWAGA: brak nmcli (NetworkManager). Skaner trackerów będzie wyłączony,"
  echo "bo bez odcisku Wi-Fi nie rozpozna zmiany miejsca."
fi

echo "== Biblioteki Pythona"
python3 -m venv .venv
.venv/bin/pip install -q --upgrade pip
.venv/bin/pip install -q -r requirements.txt

if [ ! -f "$CZUJ/env" ]; then
  cat > "$CZUJ/env" <<'ENV'
# Wpisz klucze po znaku = (bez spacji). Plik czyta tylko twój użytkownik.
ANTHROPIC_API_KEY=
GROQ_API_KEY=
# Wspólna mapa (opcjonalnie): 1 = wysyłaj anonimowe alarmy (typ, dzień, kratka ok. 2 km)
CZUJ_SHARE=0
# Przybliżona pozycja urządzenia (Linux nie ma GPS), np. 53.70 i 17.56
CZUJ_LAT=
CZUJ_LON=
ENV
  chmod 600 "$CZUJ/env"
  echo "== Utworzono $CZUJ/env: wpisz tam klucze (nano $CZUJ/env)"
fi

for arg in "$@"; do
  case "$arg" in
    --wake)
      echo "== Słowo wybudzające (Vosk, polski model)"
      .venv/bin/pip install -q vosk
      if [ ! -d "$CZUJ/vosk-model-small-pl-0.22" ]; then
        TMP=$(mktemp -d)   # własny katalog tymczasowy, nie przewidywalny plik w /tmp
        curl -fL --proto "=https" -o "$TMP/vosk-pl.zip" https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip
        unzip -q "$TMP/vosk-pl.zip" -d "$CZUJ"
        rm -rf "$TMP"
      fi
      ;;
    --service)
      echo "== Usługa systemd (użytkownika)"
      mkdir -p "$HOME/.config/systemd/user"
      cat > "$HOME/.config/systemd/user/czuj.service" <<UNIT
[Unit]
Description=Czuj - strażnik w kieszeni
After=network-online.target bluetooth.target

[Service]
WorkingDirectory=$DIR
ExecStart=$DIR/.venv/bin/python $DIR/main.py
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
UNIT
      systemctl --user daemon-reload
      systemctl --user enable --now czuj.service
      sudo loginctl enable-linger "$USER"   # start po włączeniu, bez logowania
      echo "Stan: systemctl --user status czuj | Log: journalctl --user -u czuj -f"
      ;;
  esac
done

echo "== Gotowe. Sprawdź możliwości: .venv/bin/python main.py --caps"
