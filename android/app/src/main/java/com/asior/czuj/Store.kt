package com.asior.czuj

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Ustawienia i dziennik alarmów (tylko w telefonie, prywatne dla aplikacji).
object Store {
    private const val MAX_ALERTS = 50

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("czuj", Context.MODE_PRIVATE)

    // Klucz API trzymany TYLKO zaszyfrowany (Secret = Android Keystore).
    @Volatile private var cachedKey: String? = null

    @Synchronized fun key(ctx: Context): String {
        cachedKey?.let { return it }
        val p = prefs(ctx)
        // stary zapis otwartym tekstem (wersje sprzed szyfrowania) -> szyfrujemy i usuwamy
        p.getString("anthropic_key", null)?.let { old ->
            p.edit().remove("anthropic_key").apply()
            if (old.isNotBlank()) setKey(ctx, old)
        }
        val enc = p.getString("anthropic_key_enc", null) ?: return "".also { cachedKey = it }
        val plain = Secret.decrypt(enc)
        if (plain == null) p.edit().remove("anthropic_key_enc").apply() // nieodszyfrowalny = wpisz ponownie
        return (plain ?: "").also { cachedKey = it }
    }

    /** Pusty tekst = usuń klucz. */
    @Synchronized fun setKey(ctx: Context, key: String) {
        val k = key.trim()
        val e = prefs(ctx).edit()
        if (k.isEmpty()) e.remove("anthropic_key_enc") else e.putString("anthropic_key_enc", Secret.encrypt(k))
        e.apply()
        cachedKey = k
    }

    @Synchronized fun addAlert(ctx: Context, text: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
        val list = alerts(ctx).toMutableList()
        list.add("$stamp $text")
        while (list.size > MAX_ALERTS) list.removeAt(0)
        prefs(ctx).edit().putString("alerts", JSONArray(list).toString()).apply()
    }

    fun alerts(ctx: Context): List<String> {
        val raw = prefs(ctx).getString("alerts", "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
