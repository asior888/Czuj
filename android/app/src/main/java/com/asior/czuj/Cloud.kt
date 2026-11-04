package com.asior.czuj

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Wspólna baza (Firebase, REST bez SDK). Wysyłamy TYLKO: typ trackera, dzień (UTC), kratkę ok. 2 km.
// Czytamy TYLKO mapę zbiorczą (kratki z >= 3 urządzeń, z opóźnieniem 2 dni). Reguły: firebase/firestore.rules.
object Cloud {
    // Wartości z konsoli Firebase (publiczne z założenia; dostęp chronią reguły). Puste = funkcja wyłączona.
    const val PROJECT = ""
    const val API_KEY = ""

    private const val GRID = 0.02
    val configured: Boolean get() = PROJECT.isNotEmpty() && API_KEY.isNotEmpty()
    private val docs get() = "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents"

    class HttpError(val code: Int, body: String) : IOException("HTTP $code: ${body.take(160)}")

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

    private fun neighbors(lat: Double, lon: Double): Set<String> {
        val out = HashSet<String>()
        for (dy in -1..1) for (dx in -1..1) out.add(cell(lat + dy * GRID, lon + dx * GRID))
        return out
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    // ---------- logowanie anonimowe (jedno konto na instalację) ----------

    private var idToken = ""
    private var uid = ""
    private var tokenUntil = 0L

    @Synchronized private fun auth(ctx: Context): Pair<String, String> {
        val now = System.currentTimeMillis()
        if (idToken.isNotEmpty() && now < tokenUntil) return idToken to uid
        var j: JSONObject? = null
        val refresh = Store.cloudRefresh(ctx)
        if (refresh.isNotEmpty()) {
            try {
                val r = http("https://securetoken.googleapis.com/v1/token?key=$API_KEY", "POST",
                    "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"),
                    "application/x-www-form-urlencoded", null)
                j = JSONObject().put("idToken", r.getString("id_token")).put("refreshToken", r.getString("refresh_token"))
                    .put("localId", r.getString("user_id")).put("expiresIn", r.optString("expires_in", "3600"))
            } catch (e: HttpError) {
                if (e.code != 400) throw e // 400 = token unieważniony -> nowe konto; inne błędy (sieć) nie tworzą kont
            }
        }
        val k = j ?: http("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY", "POST",
            "{\"returnSecureToken\":true}", "application/json", null)
        idToken = k.getString("idToken")
        uid = k.getString("localId")
        tokenUntil = now + ((k.optString("expiresIn", "3600").toLongOrNull() ?: 3600L) - 300) * 1000
        Store.setCloudRefresh(ctx, k.getString("refreshToken"))
        return idToken to uid
    }

    /** Wysyła alarm (blokuje, wołać poza wątkiem UI). Najwyżej raz na typ i dzień. */
    fun report(ctx: Context, tag: String, lat: Double, lon: Double) {
        if (!configured || !Store.share(ctx)) return
        val type = typeOf(tag) ?: return
        val day = today()
        val key = "${day}_$type"
        if (Store.wasSent(ctx, key)) return
        val (token, u) = auth(ctx)
        fun s(v: String) = JSONObject().put("stringValue", v)
        val body = JSONObject().put("fields", JSONObject()
            .put("uid", s(u)).put("day", s(day)).put("cell", s(cell(lat, lon)))
            .put("type", s(type)).put("platform", s("android"))
            .put("v", JSONObject().put("integerValue", "1")))
        try {
            http("$docs/reports?documentId=${u}_${key}", "POST", body.toString(), "application/json", token)
        } catch (e: HttpError) {
            if (e.code != 409) throw e // 409 = już wysłane z tego urządzenia
        }
        Store.markSent(ctx, key)
    }

    /** Mapa zbiorcza w promieniu ok. 3 km (3 x 3 kratki). null = brak danych. */
    fun nearby(lat: Double, lon: Double): Nearby? {
        if (!configured) return null
        val doc = try {
            http("$docs/stats/week?key=$API_KEY", "GET", null, null, null)
        } catch (e: HttpError) {
            if (e.code == 404) return null else throw e
        }
        val stats = JSONObject(doc.getJSONObject("fields").getJSONObject("json").getString("stringValue"))
        val cells = stats.optJSONObject("cells") ?: JSONObject()
        var total = 0; var a = 0; var t = 0; var s = 0
        for (c in neighbors(lat, lon)) {
            val o = cells.optJSONObject(c) ?: continue
            total += o.optInt("total"); a += o.optInt("airtag"); t += o.optInt("tile"); s += o.optInt("smarttag")
        }
        return Nearby(total, a, t, s, stats.optString("from"), stats.optString("to"))
    }

    private fun http(url: String, method: String, body: String?, type: String?, bearer: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type ?: "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, txt)
            return if (txt.isBlank()) JSONObject() else JSONObject(txt)
        } finally {
            c.disconnect()
        }
    }
}
