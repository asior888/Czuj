package com.asior.czuj

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Diagnostyka: wszystko zostaje W TELEFONIE. Użytkownik widzi raport i sam decyduje, czy go komuś wyśle.
// Opcjonalnie (domyślnie wyłączone) anonimowe liczniki na serwer: tylko wersja, duża wersja Androida
// i nazwa zdarzenia. Bez numeru instalacji, modelu telefonu, adresów, miejsc i treści błędów.
object Diag {
    private const val MAX_ERR = 20
    private val MAC = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences("czuj_diag", Context.MODE_PRIVATE)

    fun count(ctx: Context, what: String, n: Int = 1) {
        val pr = p(ctx)
        pr.edit().putLong("c_$what", pr.getLong("c_$what", 0) + n).apply()
    }

    private fun stamp() = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date())

    @Synchronized fun error(ctx: Context, area: String, e: Throwable) {
        val msg = MAC.replace("${e.javaClass.simpleName}: ${e.message ?: ""}", "xx:xx").take(160)
        val a = list(ctx).toMutableList()
        a.add("${stamp()} $area: $msg")
        while (a.size > MAX_ERR) a.removeAt(0)
        p(ctx).edit().putString("err", JSONArray(a).toString()).apply()
    }

    private fun list(ctx: Context): List<String> = try {
        val a = JSONArray(p(ctx).getString("err", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    /** Łapie awarie: zapis lokalny (commit, bo proces zaraz zginie), wysyłka licznika przy następnym starcie. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, prev))
    }

    private class Handler(val ctx: Context, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                error(ctx, "AWARIA", e)
                val f = e.stackTrace.firstOrNull { it.className.startsWith("com.asior.czuj") }
                val ev = "crash:" + (e.javaClass.simpleName + "." + (f?.className?.substringAfterLast('.') ?: "") +
                    "." + (f?.methodName ?: "")).replace(Regex("[^A-Za-z0-9_.$]"), "").take(80)
                p(ctx).edit().putString("pending_crash", ev).putLong("c_awarie", p(ctx).getLong("c_awarie", 0) + 1).commit()
            } catch (x: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    // ---------- bateria (próbki ze strażnika, tylko bez ładowania) ----------

    fun batterySample(ctx: Context) {
        val bm = ctx.getSystemService(BatteryManager::class.java) ?: return
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level !in 0..100 || bm.isCharging) { p(ctx).edit().remove("b0").apply(); return }
        val pr = p(ctx)
        val now = System.currentTimeMillis()
        val b0 = pr.getString("b0", null)?.split(":")
        if (b0 == null || b0.size != 2) { pr.edit().putString("b0", "$now:$level").apply(); return }
        val h = (now - b0[0].toLong()) / 3600_000.0
        if (h >= 1.0) {
            val rate = (b0[1].toInt() - level) / h
            pr.edit().putFloat("rate", rate.toFloat()).putString("b0", "$now:$level").apply()
        }
    }

    // ---------- raport ----------

    fun report(ctx: Context, caps: List<String>): String {
        val pr = p(ctx)
        val rate = pr.getFloat("rate", -1f)
        val sb = StringBuilder()
        sb.append("CZUJ: raport diagnostyczny (bez adresów urządzeń i miejsc)\n")
        sb.append("Wersja ${Updates.myCode(ctx)}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n")
        val s = GuardService.status()
        sb.append("Strażnik: ${if (s.running) "włączony" else "wyłączony"}, tryb ${Store.scanModeName(ctx)}, w pobliżu ${s.nearby}\n")
        sb.append("Liczniki: obserwacje ${pr.getLong("c_obserwacje", 0)}, alarmy ${pr.getLong("c_alarmy", 0)}, " +
            "starty strażnika ${pr.getLong("c_starty", 0)}, awarie ${pr.getLong("c_awarie", 0)}\n")
        sb.append("Bateria: " + (if (rate >= 0) String.format(Locale.ROOT, "%.1f %%/h przy włączonym strażniku", rate) else "jeszcze nie zmierzona (potrzeba 1 h bez ładowania)") + "\n")
        sb.append("\nCO DZIAŁA\n").append(caps.joinToString("\n")).append("\n")
        val err = list(ctx)
        sb.append("\nOSTATNIE BŁĘDY (${err.size})\n").append(if (err.isEmpty()) "brak" else err.reversed().joinToString("\n"))
        return sb.toString()
    }

    fun clear(ctx: Context) { p(ctx).edit().clear().apply() }

    // ---------- anonimowe liczniki (opt-in) ----------

    fun ping(ctx: Context, event: String) {
        val api = Updates.api(ctx)
        if (!Store.diagShare(ctx) || api.isEmpty()) return
        val body = JSONObject().put("v", Updates.myCode(ctx)).put("os", "android-${Build.VERSION.RELEASE.substringBefore('.')}")
            .put("event", event).toString()
        Thread { try { Cloud.post("$api/v1/diag", body) } catch (e: Exception) { } }.start()
    }

    /** Przy starcie aplikacji: raz na dobę „start” + zaległa awaria. */
    fun onStart(ctx: Context) {
        val pr = p(ctx)
        pr.getString("pending_crash", null)?.let { ping(ctx, it); pr.edit().remove("pending_crash").apply() }
        val day = System.currentTimeMillis() / 86_400_000L
        if (pr.getLong("ping_day", -1) != day) { pr.edit().putLong("ping_day", day).apply(); ping(ctx, "start") }
    }
}
