# Czuj

Osobisty strażnik w kieszeni: wykrywa nieznane trackery Bluetooth
(AirTag/Find My, Tile, SmartTag), które podróżują razem z tobą, i ostrzega głosem.

Reguła alarmu: ten sam tracker, widziany >= 4 razy, >= 25 min, w >= 2 miejscach.

## Trzy wersje

| Wersja  | Folder    | Technologia      | Co potrafi                                  | Status      |
|---------|-----------|------------------|---------------------------------------------|-------------|
| Linux   | `linux/`  | Python + bleak   | pełna: skan w tle, głos, asystent           | w budowie   |
| Android | `android/`| Kotlin (natywna) | pełna: skan w tle (usługa pierwszoplanowa)  | plan        |
| iOS     | `ios/`    | Swift            | OGRANICZONA (patrz niżej)                   | plan        |

## Ograniczenia iOS
- iOS nie podaje adresu urządzenia (tylko UUID losowy dla aplikacji), a skan w tle
  wymaga filtra po konkretnym UUID usługi. Ogólnego skanu "wszystkiego" w tle nie ma.
- Dane Apple (Find My) mogą być dla aplikacji niewidoczne: do sprawdzenia na iPhonie.
- iOS ma własne ostrzeżenia o AirTagach. Wersja iOS ma więc sens głównie dla Tile/SmartTag
  i jako asystent głosowy.
- Budowa bez Maca: GitHub Actions (macOS), podpis wymaga konta Apple Developer (99 USD/rok).

## Linux: instalacja (Raspberry Pi OS, Debian, Ubuntu)
```
cd linux
./install.sh              # podstawa
./install.sh --wake       # + słowo "Czuj" offline (Vosk, ok. 50 MB)
./install.sh --service    # + start razem z systemem
nano ~/.czuj/env          # klucze: ANTHROPIC_API_KEY, GROQ_API_KEY
.venv/bin/python main.py --caps   # co działa na tym urządzeniu
.venv/bin/python main.py          # start
```
Każda funkcja jest wykrywana osobno. Brak mikrofonu, klucza czy Bluetooth = ta funkcja
wyłączona (z powodem w `--caps`), reszta działa. Bez klucza Anthropic: brak rozmowy,
skaner i alarmy głosowe działają dalej. Alarmy: `~/.czuj/alerts.log`.
