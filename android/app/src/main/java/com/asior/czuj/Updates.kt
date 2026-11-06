package com.asior.czuj

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

// Aktualizacje i ustawienia z sieci: plik wersja.json z wydania na GitHubie (workflow dopisuje numer builda).
// Starsza wersja = pasek „Pobierz nową wersję”; poniżej „minimalna” (błąd bezpieczeństwa) = ekran blokady.
// Ten sam plik niesie adres serwera i link wsparcia, więc zmieniają się bez aktualizacji aplikacji.
object Updates {
    const val URL_INFO = "https://github.com/asior888/czuj/releases/download/android-latest/wersja.json"
    const val RELEASES = "https://github.com/asior888/czuj/releases/tag/android-latest"
    const val SUPPORT = "https://tipply.pl/asior"
    private const val CHECK_MS = 6 * 3600_000L

    class Info(val latest: Int, val min: Int, val download: String, val support: String, val api: String, val reason: String)

    enum class State { OK, BANNER, BLOCK }

    /** Link otwierany w przeglądarce: tylko https i tylko znane hosty (zepsuty plik nie przekieruje nigdzie indziej). */
    fun safe(url: String, hosts: List<String>): Boolean = try {
        val u = URI(url)
        val h = (u.host ?: "").lowercase()
        u.scheme == "https" && u.userInfo == null && hosts.any { h == it || h.endsWith(".$it") }
    } catch (e: Exception) {
        false
    }

    fun parse(txt: String): Info? = try {
        val o = JSONObject(txt)
        fun link(k: String, def: String, hosts: List<String>) = o.optString(k, "").let { if (safe(it, hosts)) it else def }
        Info(
            latest = o.optInt("najnowsza", 0),
            min = o.optInt("minimalna", 0),
            download = link("pobierz", RELEASES, listOf("github.com")),
            support = link("wsparcie", SUPPORT, listOf("tipply.pl")),
            api = o.optString("api", "").trimEnd('/').let { if (safe(it, listOf("workers.dev"))) it else "" },
            reason = o.optString("powod", "").take(300),
        )
    } catch (e: Exception) {
        null
    }

    fun state(myCode: Int, info: Info?): State = when {
        info == null -> State.OK
        info.min > myCode -> State.BLOCK
        info.latest > myCode -> State.BANNER
        else -> State.OK
    }

    // ---------- Android ----------

    fun myCode(ctx: Context): Int = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (e: Exception) {
        0
    }

    fun cached(ctx: Context): Info? = Store.updateJson(ctx).takeIf { it.isNotEmpty() }?.let { parse(it) }

    fun api(ctx: Context): String = cached(ctx)?.api ?: ""

    /** Pobiera wersja.json najwyżej co 6 h (force = zawsze). Wołać poza wątkiem UI. */
    fun refresh(ctx: Context, force: Boolean = false): Info? {
        val now = System.currentTimeMillis()
        if (!force && now - Store.updateChecked(ctx) < CHECK_MS) return cached(ctx)
        Store.setUpdateChecked(ctx, now)
        val c = URL(URL_INFO).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = true
            if (c.responseCode != 200) return cached(ctx)
            val txt = c.inputStream.bufferedReader().use { it.readText().take(4096) }
            val info = parse(txt) ?: return cached(ctx)
            Store.setUpdateJson(ctx, txt)
            info
        } catch (e: Exception) {
            Diag.error(ctx, "aktualizacje", e)
            cached(ctx)
        } finally {
            c.disconnect()
        }
    }
}
