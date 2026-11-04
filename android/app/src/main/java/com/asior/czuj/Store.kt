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

    fun key(ctx: Context): String = prefs(ctx).getString("anthropic_key", "") ?: ""

    fun setKey(ctx: Context, key: String) {
        prefs(ctx).edit().putString("anthropic_key", key.trim()).apply()
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
