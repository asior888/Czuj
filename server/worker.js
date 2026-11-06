// Serwer Czuja: Cloudflare Worker + D1. Bez kont, bez logowania, bez adminów.
// Zasada: serwer nie przechowuje niczego, co da się przypisać do osoby.
//  POST /v1/report  {dev, day, type, cell, platform}  -> zapis (raz na urządzenie, typ i dzień)
//  GET  /v1/stats                                      -> publiczna mapa zbiorcza
//  POST /v1/diag    {v, os, event}                     -> licznik +1 (bez numeru instalacji)
//  GET  /v1/diag                                       -> publiczne liczniki z 30 dni
// Codziennie (cron) agregacja: kratka widoczna od K różnych urządzeń, z opóźnieniem 2 dni.

export const K = 3;
export const WINDOW_DAYS = 7;
export const DELAY_DAYS = 2;
export const KEEP_DAYS = 9;
export const LIMIT_REPORT = 6;   // zgłoszeń z jednego IP na dobę
export const LIMIT_DIAG = 20;    // liczników z jednego IP na dobę
const TYPES = ["airtag", "tile", "smarttag"];
const PLATFORMS = ["android", "ios", "linux"];
const CELL = /^(-?\d{1,2})\.(\d{2}):(-?\d{1,3})\.(\d{2})$/;
const EVENT = /^[a-z]{1,12}(:[A-Za-z0-9_.$]{1,80})?$/;

export function dayStr(d) { return d.toISOString().slice(0, 10); }
export function addDays(day, n) {
  const d = new Date(day + "T00:00:00Z");
  d.setUTCDate(d.getUTCDate() + n);
  return dayStr(d);
}

export function validCell(c) {
  const m = CELL.exec(c || "");
  if (!m) return false;
  const lat = parseFloat(c.split(":")[0]), lon = parseFloat(c.split(":")[1]);
  // tylko wielokrotności 0,02 (kratka), żadnej dokładniejszej pozycji
  const onGrid = (v) => Math.abs(Math.round(v * 50) - v * 50) < 1e-6;
  return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && onGrid(lat) && onGrid(lon) && c !== "-0.00:0.00";
}

async function sha(s) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

function json(obj, status = 200, extra = {}) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*",
      "x-content-type-options": "nosniff", ...extra },
  });
}

async function body(req) {
  if (!(req.headers.get("content-type") || "").startsWith("application/json")) return null;
  const t = await req.text();
  if (t.length > 1024) return null;
  try { const o = JSON.parse(t); return o && typeof o === "object" && !Array.isArray(o) ? o : null; } catch { return null; }
}

/** true = wolno; liczy wywołania z jednego IP (skrót z sekretem i datą, nie sam IP). */
async function allowed(env, req, today, kind, max) {
  const ip = req.headers.get("cf-connecting-ip") || "brak";
  const k = (await sha(`${env.SALT || "czuj"}|${today}|${kind}|${ip}`)).slice(0, 24);
  const r = await env.DB.prepare(
    "INSERT INTO limits(day,k,n) VALUES(?,?,1) ON CONFLICT(day,k) DO UPDATE SET n=n+1 RETURNING n"
  ).bind(today, k).first();
  return (r ? r.n : 1) <= max;
}

export async function report(env, req, today) {
  const b = await body(req);
  if (!b) return json({ ok: false, error: "zly format" }, 400);
  const { dev, day, type, cell, platform } = b;
  if (typeof dev !== "string" || !/^[0-9a-f]{32}$/.test(dev)) return json({ ok: false, error: "dev" }, 400);
  if (day !== today && day !== addDays(today, -1)) return json({ ok: false, error: "day" }, 400);
  if (!TYPES.includes(type)) return json({ ok: false, error: "type" }, 400);
  if (!validCell(cell)) return json({ ok: false, error: "cell" }, 400);
  if (!PLATFORMS.includes(platform)) return json({ ok: false, error: "platform" }, 400);
  if (!(await allowed(env, req, today, "r", LIMIT_REPORT))) return json({ ok: false, error: "limit" }, 429);
  const d = (await sha("czuj-dev:" + dev)).slice(0, 16);
  const r = await env.DB.prepare(
    "INSERT OR IGNORE INTO reports(day,type,dev,cell,platform) VALUES(?,?,?,?,?)"
  ).bind(day, type, d, cell, platform).run();
  return json({ ok: true, new: (r.meta && r.meta.changes) > 0 });
}

export async function diagPost(env, req, today) {
  const b = await body(req);
  if (!b) return json({ ok: false, error: "zly format" }, 400);
  const v = Number.isInteger(b.v) && b.v > 0 && b.v < 1e6 ? b.v : null;
  const os = typeof b.os === "string" && /^[a-z]{2,8}-\d{1,3}$/.test(b.os) ? b.os : null;
  const ev = typeof b.event === "string" && EVENT.test(b.event) ? b.event : null;
  if (!v || !os || !ev) return json({ ok: false, error: "pola" }, 400);
  if (!(await allowed(env, req, today, "d", LIMIT_DIAG))) return json({ ok: false, error: "limit" }, 429);
  await env.DB.prepare(
    "INSERT INTO diag(day,v,os,event,n) VALUES(?,?,?,?,1) ON CONFLICT(day,v,os,event) DO UPDATE SET n=n+1"
  ).bind(today, v, os, ev).run();
  return json({ ok: true });
}

export async function diagGet(env, today) {
  const { results } = await env.DB.prepare(
    "SELECT v, os, event, SUM(n) AS n FROM diag WHERE day >= ? GROUP BY v, os, event ORDER BY n DESC LIMIT 500"
  ).bind(addDays(today, -29)).all();
  return json({ from: addDays(today, -29), to: today, rows: results }, 200, { "cache-control": "public, max-age=600" });
}

export async function aggregate(env, today) {
  const to = addDays(today, -DELAY_DAYS);
  const from = addDays(to, -(WINDOW_DAYS - 1));
  const { results } = await env.DB.prepare(
    `SELECT cell, COUNT(DISTINCT dev) AS d, COUNT(*) AS total,
       SUM(type='airtag') AS airtag, SUM(type='tile') AS tile, SUM(type='smarttag') AS smarttag
     FROM reports WHERE day BETWEEN ? AND ? GROUP BY cell HAVING d >= ? ORDER BY cell`
  ).bind(from, to, K).all();
  const cells = {};
  for (const r of results) cells[r.cell] = { total: r.total, airtag: r.airtag, tile: r.tile, smarttag: r.smarttag };
  const stats = { updated: today, from, to, k: K, grid: 0.02, cells };
  await env.DB.batch([
    env.DB.prepare("INSERT INTO stats(id,json) VALUES('week',?) ON CONFLICT(id) DO UPDATE SET json=excluded.json")
      .bind(JSON.stringify(stats)),
    env.DB.prepare("DELETE FROM reports WHERE day < ?").bind(addDays(today, -KEEP_DAYS)),
    env.DB.prepare("DELETE FROM limits WHERE day < ?").bind(today),
    env.DB.prepare("DELETE FROM diag WHERE day < ?").bind(addDays(today, -90)),
  ]);
  return stats;
}

export async function statsGet(env, today) {
  const r = await env.DB.prepare("SELECT json FROM stats WHERE id='week'").first();
  const s = r ? JSON.parse(r.json) : { updated: null, from: null, to: null, k: K, grid: 0.02, cells: {} };
  return json(s, 200, { "cache-control": "public, max-age=3600" });
}

export async function handle(req, env, now = new Date()) {
  const today = dayStr(now);
  const path = new URL(req.url).pathname;
  try {
    if (req.method === "POST" && path === "/v1/report") return await report(env, req, today);
    if (req.method === "GET" && path === "/v1/stats") return await statsGet(env, today);
    if (req.method === "POST" && path === "/v1/diag") return await diagPost(env, req, today);
    if (req.method === "GET" && path === "/v1/diag") return await diagGet(env, today);
    if (req.method === "GET" && path === "/") return json({ czuj: "ok" });
    return json({ ok: false, error: "nie ma" }, 404);
  } catch (e) {
    return json({ ok: false, error: "serwer" }, 500); // bez szczegółów na zewnątrz
  }
}

export default {
  fetch: (req, env) => handle(req, env),
  scheduled: (ev, env, ctx) => ctx.waitUntil(aggregate(env, dayStr(new Date(ev.scheduledTime)))),
};
