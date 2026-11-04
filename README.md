# Czuj

Osobisty strażnik w kieszeni. Wykrywa nieznane trackery Bluetooth (AirTag/Find My, Tile,
SmartTag), które jadą razem z tobą, ostrzega głosem i powiadomieniem. Można go zapytać
głosem albo tekstem: "czy ktoś mnie śledzi?".

## Jak działa
- **Skaner** szuka nadajników typu tracker (AirTag: dane Apple 0x12 0x19, Tile: usługa FEED/FEEC,
  SmartTag: usługa FD5A).
- **Odcisk miejsca:** 6 najsilniejszych sieci Wi-Fi (Linux, Android) albo kratka ok. 500 m z lokalizacji (iOS).
- **Alarm**, gdy ten sam tracker jest widziany >= 4 razy, >= 25 min, w >= 2 różnych miejscach.
  To samo ostrzeżenie najwyżej raz na 6 h. Tracker sąsiada stojący w jednym miejscu nie wywoła alarmu.
- **Asystent** (opcjonalny, klucz Anthropic): rozmowa o stanie skanera. Bez klucza Czuj odpowiada sam
  na podstawie skanera.
- **Każda funkcja jest sprawdzana osobno.** Czego urządzenie nie ma (mikrofon, Bluetooth, klucz),
  to jest wyłączone z podaniem powodu, a reszta działa.

## Wersje

| Wersja  | Folder     | Skan w tle | Głos | Rozmowa głosem | Słowo "Czuj" | Test w chmurze |
|---------|------------|------------|------|----------------|--------------|----------------|
| Linux   | `linux/`   | tak        | tak  | tak (Groq)     | tak (Vosk)   | testy reguł    |
| Android | `android/` | tak        | tak  | tak (Android)  | nie          | build + 5 testów |
| iOS     | `ios/`     | tylko Tile/SmartTag | tak | tak (Apple) | nie     | build + 8 testów |

## Android: instalacja
1. Repo → **Releases** → `android-latest` → pobierz `czuj.apk` na telefon.
2. Otwórz plik, zezwól na instalację z tego źródła.
3. W aplikacji: **Włącz strażnika** → zgody (lokalizacja, Bluetooth, powiadomienia).
4. Włącz lokalizację w telefonie (bez niej Android nie daje listy Wi-Fi = brak alarmów).
5. Opcjonalnie wpisz klucz Anthropic na dole ekranu.

Każdy push do `android/` na `main` sam buduje nowy APK (GitHub Actions). Kolejne wersje
instalują się na poprzednią bez utraty danych (ten sam klucz podpisu).
Klucz `android/app/czuj-debug.keystore` jest tylko do testów. Do Google Play trzeba osobnego klucza wydania.

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
Skaner wymaga NetworkManagera (`nmcli`) do odcisku miejsca. Alarmy: `~/.czuj/alerts.log`.

## iOS: ograniczenia (mówione wprost)
- W tle iOS skanuje tylko z filtrem usług, więc w tle Czuj widzi Tile i SmartTag, a AirTagi
  tylko przy otwartej aplikacji. AirTagi i tak wykrywa sam iOS (ostrzeżenia systemowe).
- iOS nie daje adresu urządzenia ani listy Wi-Fi: Czuj używa losowego identyfikatora i lokalizacji.
- Instalacja na iPhonie wymaga podpisu: konto Apple Developer (99 USD/rok) albo darmowe konto
  z Xcode na Macu (aplikacja ważna 7 dni). Build w chmurze sprawdza tylko, czy kod się kompiluje.
- Builder macOS w prywatnym repo zużywa minuty x10, więc iOS buduje się tylko przy zmianach w `ios/`.

## Prywatność i prawo
- Wszystko liczone na urządzeniu. Do internetu idzie tylko pytanie do asystenta (gdy jest klucz).
- Czuj tylko odbiera sygnały, niczego nie nadaje ani nie zagłusza.
- Pojazdów "podejrzanych" z innych źródeł niż Bluetooth (zgłoszenia ludzi) NIE ma w tej wersji:
  tablice rejestracyjne to dane osobowe (RODO), a oznaczanie ludzi grozi pozwami.
- Przy realnym zagrożeniu: 112.

## Struktura
```
linux/    Python: main, caps, scanner, alerts, stt, tts, brain, wake, install.sh
android/  Kotlin: TrackerCore (reguły + testy), GuardService, MainActivity, Brain, Voice, Store
ios/      Swift: TrackerCore (reguły + testy), Guard, Listener, Brain, CzujApp
.github/  build Androida (APK do Releases) i iOS
```
Reguły są te same we wszystkich trzech wersjach. Zmieniasz je w jednej, zmień też w pozostałych.
