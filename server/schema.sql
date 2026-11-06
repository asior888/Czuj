-- Baza Czuja (Cloudflare D1). Bez kont, bez adresów IP, bez adresów urządzeń Bluetooth.
-- reports: surowe zgłoszenia, kasowane po 9 dniach; nikt ich nie czyta z zewnątrz (brak endpointu).
CREATE TABLE IF NOT EXISTS reports (
  day TEXT NOT NULL,          -- YYYY-MM-DD (UTC)
  type TEXT NOT NULL,         -- airtag | tile | smarttag
  dev TEXT NOT NULL,          -- skrót losowego numeru instalacji (do liczenia różnych urządzeń)
  cell TEXT NOT NULL,         -- kratka ok. 2 km, np. 53.70:17.56
  platform TEXT NOT NULL,
  PRIMARY KEY (day, type, dev)
);
CREATE INDEX IF NOT EXISTS reports_day ON reports(day);

-- stats: jedna publiczna mapa zbiorcza (kratki z >= 3 urządzeń, opóźnienie 2 dni)
CREATE TABLE IF NOT EXISTS stats (
  id TEXT PRIMARY KEY,
  json TEXT NOT NULL
);

-- diag: anonimowe liczniki (wersja aplikacji, duża wersja systemu, zdarzenie) -> tylko liczba
CREATE TABLE IF NOT EXISTS diag (
  day TEXT NOT NULL,
  v INTEGER NOT NULL,
  os TEXT NOT NULL,
  event TEXT NOT NULL,
  n INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, v, os, event)
);

-- limits: ochrona przed zalewem; klucz = skrót IP z sekretem i datą, kasowany następnego dnia
CREATE TABLE IF NOT EXISTS limits (
  day TEXT NOT NULL,
  k TEXT NOT NULL,
  n INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, k)
);
