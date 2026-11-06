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
- **Sterowanie głosem bez internetu:** „czy ktoś mnie śledzi”, „skanuj”, „pokaż mapę”, „ustawienia”,
  „wycisz na godzinę”, „włącz alarmy”, „to mój tracker”, „włącz/wyłącz strażnika”, „włącz/wyłącz
  udostępnianie”, „diagnostyka”, „pomoc”. Asystent AI (klucz Anthropic) jest opcją do swobodnych pytań.
- **Moje urządzenia:** własny tracker oznaczasz „To moje” (w powiadomieniu albo głosem) i nie daje alarmów.
- **Trasa:** gdzie ten sam tracker był przy tobie (7 dni, tylko w telefonie, zaszyfrowana). Mapa + plik CSV
  jako dowód, np. dla policji.
- **Wspólna mapa** (opcjonalna, za zgodą): anonimowe alarmy ze wszystkich urządzeń składają się
  w publiczną mapę "ile alarmów w okolicy w ostatnich 7 dniach". Szczegóły niżej.
- **Każda funkcja jest sprawdzana osobno.** Czego urządzenie nie ma (mikrofon, Bluetooth, klucz),
  to jest wyłączone z podaniem powodu, a reszta działa.

## Wersje

| Wersja  | Folder     | Skan w tle | Głos | Rozmowa głosem | Słowo "Czuj" | Test w chmurze |
|---------|------------|------------|------|----------------|--------------|----------------|
| Linux   | `linux/`   | tak        | tak  | tak (Groq)     | tak (Vosk)   | testy reguł i mapy |
| Android | `android/` | tak        | tak  | tak + komendy offline | nie (w planie) | build + testy jednostkowe |
| iOS     | `ios/`     | tylko Tile/SmartTag | tak | tak (Apple) | nie     | build + 13 testów |
| Serwer  | `server/`  | -          | -    | -              | -            | 8 testów (SQLite) |

Pełny wygląd (3 zakładki: Strażnik, Mapa, Ustawienia; mapa trasy; ustawienia; kopia; diagnostyka) ma
na razie tylko Android. iOS i Linux mają wykrywanie, głos i wspólną mapę.

## Android: instalacja
1. Repo → **Releases** → `android-latest` → pobierz `czuj.apk` na telefon.
2. Otwórz plik, zezwól na instalację z tego źródła.
3. W aplikacji: **Włącz strażnika** → zgody (lokalizacja, Bluetooth, powiadomienia).
4. Włącz lokalizację w telefonie (bez niej Android nie daje listy Wi-Fi = brak alarmów).
5. Mikrofon w prawym dolnym rogu: powiedz „pomoc”, żeby usłyszeć komendy.

**Aktualizacje:** aplikacja co 6 h czyta `wersja.json` z wydania. Nowsza wersja = pasek „Pobierz nową
wersję”. Pole `minimalna` w `wersja.json` (tylko przy błędzie bezpieczeństwa, z opisem w `powod`)
blokuje starsze wersje, dopóki ktoś nie zaktualizuje. Ten sam plik niesie adres serwera i link wsparcia.

Każdy push do `android/` na `main` sam buduje i podpisuje nowy APK (GitHub Actions). Kolejne wersje
instalują się na poprzednią bez utraty danych. Sumę `sha256` i odcisk certyfikatu podaje opis wydania.

## Linux: instalacja (Raspberry Pi OS, Debian, Ubuntu)
```
cd linux
./install.sh              # podstawa
./install.sh --wake       # + słowo "Czuj" offline (Vosk, ok. 50 MB)
./install.sh --service    # + start razem z systemem
nano ~/.czuj/env          # klucze + opcjonalnie CZUJ_SHARE=1, CZUJ_LAT, CZUJ_LON, CZUJ_API (mapa)
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

## Wspólna mapa (serwer Cloudflare, bez kont)
**Zasada: serwer nie przechowuje niczego, co da się przypisać do osoby.** Nie ma kont, logowania ani
adminów, bo nie ma czego oglądać.

**Co wysyła telefon** (tylko po włączeniu „Udostępniaj alarmy”): losowy numer instalacji (da się zresetować
w ustawieniach; serwer trzyma tylko jego skrót), typ trackera, dzień (UTC), kratka ok. 2 km, platforma.
Bez adresów urządzeń, godziny, dokładnej pozycji, nazwy, numeru telefonu.

**Co widzą wszyscy** (`GET /v1/stats`): mapa zbiorcza z 7 dni. Kratka pokazuje się dopiero, gdy zgłosiły ją
**co najmniej 3 różne telefony**, a dni są opóźnione o 2. Powód: surowe alarmy pokazywałyby, gdzie jest
osoba śledzona i że właśnie znalazła tracker. Surowych zgłoszeń nie da się odczytać (nie ma takiego
adresu), kasowane są po 9 dniach. Agregacja: cron Cloudflare codziennie 03:17 UTC.

**Ochrona przed fałszywkami:** najwyżej 6 zgłoszeń dziennie z jednego adresu IP (serwer trzyma tylko skrót
IP z sekretem i datą, kasowany następnego dnia), 1 zgłoszenie na telefon, typ i dzień, ścisła kontrola pól.
Dzienniki żądań Cloudflare wyłączone (`observability = false`).
Czego to NIE zatrzyma (wprost): ktoś z wieloma adresami IP może nabić fałszywe alarmy w jednej kratce.

**Diagnostyka:** raport zostaje w telefonie (Ustawienia → Diagnostyka → Kopiuj raport) i wysyła go tylko
użytkownik. Opcjonalnie (domyślnie wyłączone) anonimowe liczniki: wersja aplikacji, wersja Androida,
„start” albo nazwa awarii. Bez numeru instalacji. Publiczne: `GET /v1/diag`.

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
- **Mapa:** opis wyżej. Token Cloudflare tylko w sekrecie `CF_API_TOKEN`, nigdy w repo.
- **Kopia ustawień:** plik szyfrowany hasłem (AES-256-GCM, PBKDF2 310 tys.), bez klucza AI i trasy.

## Prywatność i prawo
- Wszystko liczone na urządzeniu. Do internetu idzie tylko pytanie do asystenta (gdy jest klucz)
  i, za zgodą, anonimowy alarm do wspólnej mapy. Baza D1 w regionie Europa Zachodnia (weur).
- Mapa w aplikacji pobiera kafelki z OpenFreeMap (bez klucza i śledzenia); trasa nigdy nie opuszcza telefonu.
- Czuj tylko odbiera sygnały, niczego nie nadaje ani nie zagłusza.
- Pojazdów "podejrzanych" z innych źródeł niż Bluetooth (zgłoszenia ludzi) NIE ma w tej wersji:
  tablice rejestracyjne to dane osobowe (RODO), a oznaczanie ludzi grozi pozwami.
- Przy realnym zagrożeniu: 112.

## Struktura
```
linux/     Python: main, caps, scanner, alerts, stt, tts, brain, wake, cloud, install.sh
android/   Kotlin: TrackerCore, GuardService, MainActivity + GuardTab/MapTab/SettingsTab, Commands,
           Updates, Diag, Backup, Trail, Brain, Voice, Store, Secret, Cloud, Where; assets/map (MapLibre)
ios/       Swift: TrackerCore, Guard, Listener, Brain, Keychain, Cloud, CzujApp
server/    Cloudflare Worker + D1: worker.js, schema.sql, test.mjs, wrangler.toml
wersja.json  najnowsza/minimalna wersja, link pobierania, link wsparcia, adres serwera
.github/   Android (podpis + Releases), iOS, serwer (testy + wdrożenie), klucz podpisu
```
Reguły są te same we wszystkich trzech wersjach. Zmieniasz je w jednej, zmień też w pozostałych.

## Uruchomienie (właściciel repo, raz)
**1. Klucz podpisu** (najpierw, przed upublicznieniem repo):
Settings → Secrets and variables → Actions → New repository secret → `CZUJ_SIGN_PASS` = własne
hasło, min. 20 znaków (zapisz je; bez niego nie wydasz aktualizacji). Potem Actions →
"Klucz podpisu (jednorazowo)" → Run workflow.

**2. Serwer (Cloudflare, darmowy plan):** dash.cloudflare.com → konto → Workers & Pages (wybierz subdomenę
`*.workers.dev`) → Account ID z paska adresu → My Profile → API Tokens → Create Token → szablon
„Edit Cloudflare Workers” + uprawnienie Account → D1 → Edit. Sekrety w GitHubie: `CF_ACCOUNT_ID`,
`CF_API_TOKEN` (nie do czatu). Potem Actions → „Serwer (Cloudflare)” → Run workflow: zakłada bazę,
wdraża serwer, adres trafia do gałęzi `cloud-result` → wpisać go w `wersja.json` (pole `api`),
`Cloud.swift` i `CZUJ_API` na Linuksie.

**3. Publiczne repo:** Settings → General → Danger Zone → Change visibility → Public.
