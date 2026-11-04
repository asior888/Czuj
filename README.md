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
- **Wspólna mapa** (opcjonalna, za zgodą): anonimowe alarmy ze wszystkich urządzeń składają się
  w publiczną mapę "ile alarmów w okolicy w ostatnich 7 dniach". Szczegóły niżej.
- **Każda funkcja jest sprawdzana osobno.** Czego urządzenie nie ma (mikrofon, Bluetooth, klucz),
  to jest wyłączone z podaniem powodu, a reszta działa.

## Wersje

| Wersja  | Folder     | Skan w tle | Głos | Rozmowa głosem | Słowo "Czuj" | Test w chmurze |
|---------|------------|------------|------|----------------|--------------|----------------|
| Linux   | `linux/`   | tak        | tak  | tak (Groq)     | tak (Vosk)   | testy reguł i mapy |
| Android | `android/` | tak        | tak  | tak (Android)  | nie          | build + 8 testów |
| iOS     | `ios/`     | tylko Tile/SmartTag | tak | tak (Apple) | nie     | build + 13 testów |
| Baza    | `firebase/`| -          | -    | -              | -            | 17 testów reguł + 7 agregatora |

## Android: instalacja
1. Repo → **Releases** → `android-latest` → pobierz `czuj.apk` na telefon.
2. Otwórz plik, zezwól na instalację z tego źródła.
3. W aplikacji: **Włącz strażnika** → zgody (lokalizacja, Bluetooth, powiadomienia).
4. Włącz lokalizację w telefonie (bez niej Android nie daje listy Wi-Fi = brak alarmów).
5. Opcjonalnie wpisz klucz Anthropic na dole ekranu.

Każdy push do `android/` na `main` sam buduje i podpisuje nowy APK (GitHub Actions). Kolejne wersje
instalują się na poprzednią bez utraty danych. Sumę `sha256` i odcisk certyfikatu podaje opis wydania.

## Linux: instalacja (Raspberry Pi OS, Debian, Ubuntu)
```
cd linux
./install.sh              # podstawa
./install.sh --wake       # + słowo "Czuj" offline (Vosk, ok. 50 MB)
./install.sh --service    # + start razem z systemem
nano ~/.czuj/env          # klucze + opcjonalnie CZUJ_SHARE=1, CZUJ_LAT, CZUJ_LON (mapa)
.venv/bin/python main.py --caps   # co działa na tym urządzeniu
.venv/bin/python main.py          # start
```
Skaner wymaga NetworkManagera (`nmcli`) do odcisku miejsca. Alarmy: `~/.czuj/alerts.log`.
Mapa okolicy: `.venv/bin/python cloud.py okolica`.

## iOS: ograniczenia (mówione wprost)
- W tle iOS skanuje tylko z filtrem usług, więc w tle Czuj widzi Tile i SmartTag, a AirTagi
  tylko przy otwartej aplikacji. AirTagi i tak wykrywa sam iOS (ostrzeżenia systemowe).
- iOS nie daje adresu urządzenia ani listy Wi-Fi: Czuj używa losowego identyfikatora i lokalizacji.
- Instalacja na iPhonie wymaga podpisu: konto Apple Developer (99 USD/rok) albo darmowe konto
  z Xcode na Macu (aplikacja ważna 7 dni). Build w chmurze sprawdza tylko, czy kod się kompiluje.
- iOS buduje się tylko przy zmianach w `ios/` (macOS w GitHub Actions jest wolniejszy).

## Wspólna mapa (Firebase)
**Co wysyła urządzenie** (tylko po włączeniu "Udostępniaj alarmy"): typ trackera, dzień (UTC),
kratka ok. 2 x 1,3 km, platforma. Nic więcej: bez adresów urządzeń, godziny, dokładnej pozycji,
nazwy, numeru. Konto jest anonimowe (losowy identyfikator instalacji).

**Co widzą wszyscy:** mapa zbiorcza z ostatnich 7 dni. Kratka pokazuje się dopiero, gdy zgłosiły ją
**co najmniej 3 różne urządzenia**, a dni są opóźnione o 2 (dziś i wczoraj nie widać).
Powód: surowe alarmy pokazywałyby, gdzie jest osoba śledzona i że właśnie znalazła tracker.
Takiej mapy szukałby sprawca. Surowych zgłoszeń nie czyta nikt (nawet autor), kasowane są po 7 dniach.

**Zabezpieczenia (reguły `firebase/firestore.rules`, 17 testów na emulatorze):**
dopisywać może tylko zalogowane urządzenie i tylko w swoim imieniu, 1 zgłoszenie na typ i dzień,
dokładnie 6 dozwolonych pól, kratka tylko z 2 miejscami po przecinku, data tylko z dziś.
Mapę liczy agregator w GitHub Actions (codziennie 03:17 UTC) kontem serwisowym z sekretu.

**Czego to NIE zatrzyma (wprost):** ktoś może założyć wiele anonimowych kont i nabić fałszywe
alarmy w jednej kratce. Próg 3 urządzeń to utrudnia, ale nie blokuje. Kolejny krok:
Firebase App Check (tylko prawdziwa aplikacja z Google Play / App Store może pisać).

## Bezpieczeństwo
- **Klucz AI:** Android szyfruje go kluczem AES-256 z Android Keystore (klucz nie opuszcza telefonu),
  iOS trzyma go w pęku kluczy tylko na tym urządzeniu, Linux w `~/.czuj/env` z prawami 600.
  Pusty klucz + Zapisz = usunięcie. Klucze nigdy nie trafiają do repo.
- **Android:** bez kopii zapasowej danych aplikacji, tylko HTTPS, ekran zablokowany dla zrzutów
  i podglądu ostatnich aplikacji (FLAG_SECURE), usługa w tle niedostępna dla innych aplikacji.
- **Linux:** katalog `~/.czuj` 700, dziennik alarmów 600 (zdradza, kiedy gdzie byłeś),
  ostrzeżenie, gdy plik z kluczami czytają inni.
- **Budowanie:** kompilacja ma tylko prawo odczytu repo. Zapis (wydanie APK) robi osobne zadanie,
  które nie uruchamia cudzego kodu. W opisie wydania jest suma `sha256` pliku APK.
- **Podpis APK:** klucz wydania leży w repo tylko zaszyfrowany (AES-256, PBKDF2 600 tys.), hasło jest
  w sekrecie GitHub `CZUJ_SIGN_PASS`. APK podpisuje osobne zadanie (`apksigner`), bez kodu z builda.
  Stary klucz testowy (`czuj-debug.keystore`, hasło `android`) jest w historii repo: wersje nim
  podpisane trzeba odinstalować przed instalacją nowej.
- **Mapa:** opis wyżej. Konto serwisowe Firebase tylko w sekrecie `FIREBASE_SA`, nigdy w repo.

## Prywatność i prawo
- Wszystko liczone na urządzeniu. Do internetu idzie tylko pytanie do asystenta (gdy jest klucz)
  i, za zgodą, anonimowy alarm do wspólnej mapy. Bazę stawiamy w regionie UE (eur3).
- Czuj tylko odbiera sygnały, niczego nie nadaje ani nie zagłusza.
- Pojazdów "podejrzanych" z innych źródeł niż Bluetooth (zgłoszenia ludzi) NIE ma w tej wersji:
  tablice rejestracyjne to dane osobowe (RODO), a oznaczanie ludzi grozi pozwami.
- Przy realnym zagrożeniu: 112.

## Struktura
```
linux/     Python: main, caps, scanner, alerts, stt, tts, brain, wake, cloud, install.sh
android/   Kotlin: TrackerCore, GuardService, MainActivity, Brain, Voice, Store, Secret, Cloud, Where
ios/       Swift: TrackerCore, Guard, Listener, Brain, Keychain, Cloud, CzujApp
firebase/  reguły bazy, agregator mapy, testy (emulator)
.github/   Android (podpis + Releases), iOS, baza (testy, reguły, agregacja), klucz podpisu
```
Reguły są te same we wszystkich trzech wersjach. Zmieniasz je w jednej, zmień też w pozostałych.

## Uruchomienie (właściciel repo, raz)
**1. Klucz podpisu** (najpierw, przed upublicznieniem repo):
Settings → Secrets and variables → Actions → New repository secret → `CZUJ_SIGN_PASS` = własne
hasło, min. 20 znaków (zapisz je; bez niego nie wydasz aktualizacji). Potem Actions →
"Klucz podpisu (jednorazowo)" → Run workflow.

**2. Firebase:** console.firebase.google.com → Dodaj projekt → Authentication → Anonymous: włącz →
Firestore Database → Utwórz (tryb produkcyjny, region eur3) → Ustawienia projektu → Twoje aplikacje →
Web: `projectId` i `apiKey` (wartości publiczne) wpisać w `Cloud.kt`, `Cloud.swift`, `linux/config.py`.
Konta usługi → Wygeneruj klucz prywatny → całą zawartość pliku JSON jako sekret `FIREBASE_SA`
(nigdzie indziej, nie do czatu). Potem Actions → "Baza (Firebase)" → Run workflow (wdraża reguły).

**3. Publiczne repo:** Settings → General → Danger Zone → Change visibility → Public.
