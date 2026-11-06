package com.asior.czuj

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Wspólna mapa: serwer Cloudflare (server/worker.js). Bez kont i logowania.
// Wysyłamy TYLKO: losowy numer instalacji (do liczenia różnych urządzeń, da się zresetować), typ trackera,
// dzień (UTC), kratkę ok. 2 km. Czytamy TYLKO mapę zbiorczą (kratki z >= 3 urządzeń, opóźnienie 2 dni).
// Adres serwera przychodzi z wersja.json (Updates), więc pusty = funkcja wyłączona.
object Cloud {
    const val GRID = 0.02

    class Nearby(val total: Int, val airtag: Int, val tile: Int, val smarttag: Int, val from: String, val to: String)

    fun typeOf(tag: String): String? = when {
        tag.startsWith("AirTag") -> "airtag"
        tag.startsWith("Tile") -> "tile"
        tag.startsWith("SmartTag") -> "smarttag"
        else -> null
    }

    fun cell(lat: Double, lon: Double): String {
        fun r(v: Double) = Math.round(v / GRID) * GRID
        return String.format(Locale.ROOT, "%.2f:%.2f", r(lat), r(lon)).replace("-0.00", "0.00")
    }

    fun neighbors(lat: Double, lon: Double, ring: Int = 1): Set<String> {
        val out = HashSet<String>()
        for (dy in -ring..ring) for (dx in -ring..ring) out.add(cell(lat + dy * GRID, lon + dx * GRID))
        return out
    }

    fun configured(ctx: Context) = Updates.api(ctx).isNotEmpty()

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    /** Wysyła alarm (blokuje, wołać poza wątkiem UI). Najwyżej raz na typ i dzień. */
    fun report(ctx: Context, tag: String, lat: Double, lon: Double) {
        val api = Updates.api(ctx)
        if (api.isEmpty() || !Store.share(ctx)) return
        val type = typeOf(tag) ?: return
        val day = today()
        val key = "${day}_$type"
        if (Store.wasSent(ctx, key)) return
        val body = JSONObject().put("dev", Store.installId(ctx)).put("day", day).put("type", type)
            .put("cell", cell(lat, lon)).put("platform", "android").toString()
        post("$api/v1/report", body)
        Store.markSent(ctx, key)
    }

    @Volatile private var stats: JSONObject? = null
    @Volatile private var statsAt = 0L

    /** Cała mapa zbiorcza (pamięć 1 h). null = serwer nie ustawiony albo mapa jeszcze pusta. */
    fun stats(ctx: Context): JSONObject? {
        val api = Updates.api(ctx)
        if (api.isEmpty()) return null
        val now = System.currentTimeMillis()
        stats?.let { if (now - statsAt < 3600_000L) return it }
        val s = JSONObject(get("$api/v1/stats"))
        stats = s; statsAt = now
        return s
    }

    /** Okolica: 3 x 3 kratki (ok. 3 km). */
    fun nearby(ctx: Context, lat: Double, lon: Double): Nearby? {
        val s = stats(ctx) ?: return null
        val cells = s.optJSONObject("cells") ?: JSONObject()
        var total = 0; var a = 0; var t = 0; var m = 0
        for (c in neighbors(lat, lon)) {
            val o = cells.optJSONObject(c) ?: continue
            total += o.optInt("total"); a += o.optInt("airtag"); t += o.optInt("tile"); m += o.optInt("smarttag")
        }
        return Nearby(total, a, t, m, s.optString("from"), s.optString("to"))
    }

    fun post(url: String, body: String): String = http(url, "POST", body)
    private fun get(url: String): String = http(url, "GET", null)

    private fun http(url: String, method: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("serwer $code")
            return txt
        } finally {
            c.disconnect()
        }
    }
}
