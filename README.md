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

## Linux (start)
cd linux && pip install -r requirements.txt
Klucze jako zmienne środowiskowe (nigdy w repo):
export ANTHROPIC_API_KEY="..."  export GROQ_API_KEY="..."
Testy: python stt.py | python tts.py | python brain.py | python scanner.py
