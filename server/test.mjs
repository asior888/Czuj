// Testy serwera bez Cloudflare: D1 zastąpione wbudowanym SQLite z Node 22 (ta sama składnia SQL).
// Uruchom:  node --test server/test.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { handle, aggregate, validCell, addDays, LIMIT_REPORT } from "./worker.js";

function fakeD1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("./schema.sql", import.meta.url), "utf8"));
  const stmt = (sql, args = []) => ({
    bind: (...a) => stmt(sql, a),
    first: async () => db.prepare(sql).get(...args) ?? null,
    all: async () => ({ results: db.prepare(sql).all(...args) }),
    run: async () => { const r = db.prepare(sql).run(...args); return { meta: { changes: Number(r.changes) } }; },
  });
  return { raw: db, prepare: (sql) => stmt(sql), batch: async (list) => { for (const s of list) await s.run(); } };
}

const NOW = new Date("2026-11-10T12:00:00Z");
const TODAY = "2026-11-10";
const env = () => ({ DB: fakeD1(), SALT: "test" });
const dev = (i) => i.toString(16).padStart(32, "0");
const post = (path, obj, ip = "1.1.1.1") => new Request("https://x" + path, {
  method: "POST", headers: { "content-type": "application/json", "cf-connecting-ip": ip }, body: JSON.stringify(obj),
});
const rep = (i, extra = {}) => ({ dev: dev(i), day: TODAY, type: "airtag", cell: "53.70:17.56", platform: "android", ...extra });

test("kratka: tylko siatka 0,02 i zakresy", () => {
  assert.ok(validCell("53.70:17.56"));
  assert.ok(validCell("-33.86:151.20"));
  assert.ok(!validCell("53.71:17.56"), "nie na siatce");
  assert.ok(!validCell("53.7012:17.56"), "za dokładnie");
  assert.ok(!validCell("95.00:17.56"));
  assert.ok(!validCell("53.70"));
});

test("zgłoszenie: poprawne przyjęte, powtórka tego samego dnia nie liczy się", async () => {
  const e = env();
  let r = await handle(post("/v1/report", rep(1)), e, NOW);
  assert.equal(r.status, 200);
  assert.equal((await r.json()).new, true);
  r = await handle(post("/v1/report", rep(1)), e, NOW);
  assert.equal((await r.json()).new, false);
  assert.equal(e.DB.raw.prepare("SELECT COUNT(*) c FROM reports").get().c, 1);
});

test("zgłoszenie: odrzuca złe pola i dodatkowe dane nie trafiają do bazy", async () => {
  const e = env();
  const bad = [
    rep(1, { dev: "xyz" }), rep(1, { day: "2026-11-01" }), rep(1, { type: "car" }),
    rep(1, { cell: "53.7012:17.5601" }), rep(1, { platform: "web" }),
  ];
  for (const b of bad) assert.equal((await handle(post("/v1/report", b), e, NOW)).status, 400, JSON.stringify(b));
  const r = await handle(post("/v1/report", rep(2, { mac: "AA:BB", lat: 53.7012 })), e, NOW);
  assert.equal(r.status, 200);
  const row = e.DB.raw.prepare("SELECT * FROM reports").get();
  assert.deepEqual(Object.keys(row).sort(), ["cell", "day", "dev", "platform", "type"]);
  assert.notEqual(row.dev, dev(2), "numer instalacji zapisany tylko jako skrót");
  const big = new Request("https://x/v1/report", { method: "POST", headers: { "content-type": "application/json" }, body: "x".repeat(2000) });
  assert.equal((await handle(big, e, NOW)).status, 400);
});

test("limit z jednego IP na dobę, IP nie zapisany w bazie", async () => {
  const e = env();
  for (let i = 0; i < LIMIT_REPORT; i++) assert.equal((await handle(post("/v1/report", rep(i)), e, NOW)).status, 200);
  assert.equal((await handle(post("/v1/report", rep(99)), e, NOW)).status, 429);
  assert.equal((await handle(post("/v1/report", rep(99), "2.2.2.2"), e, NOW)).status, 200, "inne IP");
  const dump = JSON.stringify(e.DB.raw.prepare("SELECT * FROM limits").all());
  assert.ok(!dump.includes("1.1.1.1"));
});

test("mapa: kratka dopiero od 3 urządzeń, z opóźnieniem 2 dni, okno 7 dni", async () => {
  const e = env();
  const ins = (day, d, cell, type = "airtag") => e.DB.raw.prepare(
    "INSERT INTO reports(day,type,dev,cell,platform) VALUES(?,?,?,?,'android')").run(day, type, d, cell);
  const d3 = addDays(TODAY, -3);
  ins(d3, "a", "53.70:17.56"); ins(d3, "b", "53.70:17.56"); ins(d3, "c", "53.70:17.56", "tile");
  ins(addDays(TODAY, -4), "a", "50.00:20.00"); ins(addDays(TODAY, -4), "b", "50.00:20.00");           // 2 urządzenia: ukryta
  ins(TODAY, "x", "52.00:21.00"); ins(TODAY, "y", "52.00:21.00"); ins(TODAY, "z", "52.00:21.00"); // za świeże
  ins(addDays(TODAY, -12), "p", "51.00:19.00");                        // do skasowania
  const s = await aggregate(e, TODAY);
  assert.deepEqual(Object.keys(s.cells), ["53.70:17.56"]);
  assert.deepEqual(s.cells["53.70:17.56"], { total: 3, airtag: 2, tile: 1, smarttag: 0 });
  assert.equal(s.to, addDays(TODAY, -2));
  assert.equal(s.from, addDays(TODAY, -8));
  assert.equal(e.DB.raw.prepare("SELECT COUNT(*) c FROM reports WHERE day < ?").get(addDays(TODAY, -9)).c, 0);
  const r = await handle(new Request("https://x/v1/stats"), e, NOW);
  assert.deepEqual((await r.json()).cells, s.cells);
});

test("mapa pusta przed pierwszą agregacją", async () => {
  const r = await handle(new Request("https://x/v1/stats"), env(), NOW);
  assert.equal(r.status, 200);
  assert.deepEqual((await r.json()).cells, {});
});

test("diagnostyka: tylko licznik, bez numeru instalacji", async () => {
  const e = env();
  assert.equal((await handle(post("/v1/diag", { v: 14, os: "android-16", event: "start" }), e, NOW)).status, 200);
  await handle(post("/v1/diag", { v: 14, os: "android-16", event: "start", dev: dev(1) }), e, NOW);
  assert.equal((await handle(post("/v1/diag", { v: 14, os: "android-16", event: "crash:Guard.handle" }), e, NOW)).status, 200);
  assert.equal((await handle(post("/v1/diag", { v: 14, os: "android-16", event: "start; DROP TABLE" }), e, NOW)).status, 400);
  const rows = e.DB.raw.prepare("SELECT * FROM diag ORDER BY event").all();
  assert.equal(rows.length, 2);
  assert.equal(rows[1].n, 2);
  assert.ok(!JSON.stringify(rows).includes(dev(1)));
  const g = await (await handle(new Request("https://x/v1/diag"), e, NOW)).json();
  assert.equal(g.rows.length, 2);
});

test("nieznana ścieżka 404, błąd bazy nie zdradza szczegółów", async () => {
  assert.equal((await handle(new Request("https://x/admin"), env(), NOW)).status, 404);
  const broken = { DB: { prepare: () => { throw new Error("tajne szczegóły"); } } };
  const r = await handle(new Request("https://x/v1/stats"), broken, NOW);
  assert.equal(r.status, 500);
  assert.ok(!(await r.text()).includes("tajne"));
});
