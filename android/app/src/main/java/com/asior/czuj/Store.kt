package com.asior.czuj

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Ustawienia, dziennik alarmów, moje urządzenia i trasa: tylko w telefonie, prywatne dla aplikacji.
object Store {
    private const val MAX_ALERTS = 50

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("czuj", Context.MODE_PRIVATE)

    // ---------- klucz AI (TYLKO zaszyfrowany, Android Keystore) ----------

    @Volatile private var cachedKey: String? = null

    @Synchronized fun key(ctx: Context): String {
        cachedKey?.let { return it }
        val p = prefs(ctx)
        p.getString("anthropic_key", null)?.let { old ->
            p.edit().remove("anthropic_key").apply()
            if (old.isNotBlank()) setKey(ctx, old)
        }
        val enc = p.getString("anthropic_key_enc", null) ?: return "".also { cachedKey = it }
        val plain = Secret.decrypt(enc)
        if (plain == null) p.edit().remove("anthropic_key_enc").apply()
        return (plain ?: "").also { cachedKey = it }
    }

    @Synchronized fun setKey(ctx: Context, key: String) {
        val k = key.trim()
        val e = prefs(ctx).edit()
        if (k.isEmpty()) e.remove("anthropic_key_enc") else e.putString("anthropic_key_enc", Secret.encrypt(k))
        e.apply()
        cachedKey = k
    }

    // ---------- ustawienia ----------

    val SCAN_MODES = listOf("oszczędny", "normalny", "czuły")
    fun scanMode(ctx: Context) = prefs(ctx).getInt("scan_mode", 0).coerceIn(0, 2)
    fun scanModeName(ctx: Context) = SCAN_MODES[scanMode(ctx)]
    fun setScanMode(ctx: Context, m: Int) { prefs(ctx).edit().putInt("scan_mode", m.coerceIn(0, 2)).apply() }

    fun voiceOn(ctx: Context) = prefs(ctx).getBoolean("voice_on", true)
    fun setVoiceOn(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("voice_on", on).apply() }

    val RATES = listOf(0.8f, 1.0f, 1.25f)
    fun voiceRate(ctx: Context) = prefs(ctx).getFloat("voice_rate", 1.0f)
    fun setVoiceRate(ctx: Context, r: Float) { prefs(ctx).edit().putFloat("voice_rate", r).apply() }

    /** Zgoda na wysyłanie anonimowych alarmów na wspólną mapę. Domyślnie WYŁĄCZONA. */
    fun share(ctx: Context): Boolean = prefs(ctx).getBoolean("share", false)
    fun setShare(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("share", on).apply() }

    /** Anonimowe liczniki diagnostyczne. Domyślnie WYŁĄCZONE. */
    fun diagShare(ctx: Context): Boolean = prefs(ctx).getBoolean("diag_share", false)
    fun setDiagShare(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("diag_share", on).apply() }

    // ---------- wyciszenie ----------

    fun muteUntil(ctx: Context) = prefs(ctx).getLong("mute_until", 0)
    fun muted(ctx: Context) = System.currentTimeMillis() < muteUntil(ctx)
    fun setMute(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putLong("mute_until", if (minutes <= 0) 0 else System.currentTimeMillis() + minutes * 60_000L).apply()
    }

    // ---------- moje urządzenia (nie dają alarmów) ----------

    private fun mineObj(ctx: Context): JSONObject = try { JSONObject(prefs(ctx).getString("mine", "{}")) } catch (e: Exception) { JSONObject() }

    fun mine(ctx: Context): Map<String, String> {
        val o = mineObj(ctx)
        return o.keys().asSequence().associateWith { o.optString(it) }
    }

    fun isMine(ctx: Context, addr: String) = mineObj(ctx).has(addr)

    @Synchronized fun addMine(ctx: Context, addr: String, tag: String) {
        val o = mineObj(ctx)
        val day = SimpleDateFormat("dd.MM", Locale.ROOT).format(Date())
        o.put(addr, "$tag, dodany $day")
        while (o.length() > 50) o.remove(o.keys().next())
        prefs(ctx).edit().putString("mine", o.toString()).apply()
    }

    @Synchronized fun removeMine(ctx: Context, addr: String) {
        val o = mineObj(ctx); o.remove(addr)
        prefs(ctx).edit().putString("mine", o.toString()).apply()
    }

    // ---------- numer instalacji (losowy, tylko do liczenia różnych urządzeń na mapie) ----------

    @Synchronized fun installId(ctx: Context): String {
        prefs(ctx).getString("install_id", null)?.let { return it }
        return resetInstallId(ctx)
    }

    @Synchronized fun resetInstallId(ctx: Context): String {
        val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val id = b.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString("install_id", id).remove("sent").apply()
        return id
    }

    @Synchronized fun wasSent(ctx: Context, key: String): Boolean = sentList(ctx).contains(key)

    @Synchronized fun markSent(ctx: Context, key: String) {
        val l = sentList(ctx).toMutableList()
        l.add(key)
        while (l.size > 30) l.removeAt(0)
        prefs(ctx).edit().putString("sent", JSONArray(l).toString()).apply()
    }

    private fun sentList(ctx: Context): List<String> = try {
        val a = JSONArray(prefs(ctx).getString("sent", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    // ---------- aktualizacje ----------

    fun updateJson(ctx: Context) = prefs(ctx).getString("update_json", "") ?: ""
    fun setUpdateJson(ctx: Context, s: String) { prefs(ctx).edit().putString("update_json", s).apply() }
    fun updateChecked(ctx: Context) = prefs(ctx).getLong("update_checked", 0)
    fun setUpdateChecked(ctx: Context, t: Long) { prefs(ctx).edit().putLong("update_checked", t).apply() }

    // ---------- alarmy ----------

    @Synchronized fun addAlert(ctx: Context, text: String) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
        val list = alerts(ctx).toMutableList()
        list.add("$stamp $text")
        while (list.size > MAX_ALERTS) list.removeAt(0)
        prefs(ctx).edit().putString("alerts", JSONArray(list).toString()).apply()
    }

    fun alerts(ctx: Context): List<String> = try {
        val a = JSONArray(prefs(ctx).getString("alerts", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    fun clearAlerts(ctx: Context) { prefs(ctx).edit().remove("alerts").remove("alert_ids").apply() }

    /** Trackery, które dały alarm (do koloru na mapie). */
    fun alertIds(ctx: Context): Set<String> = prefs(ctx).getStringSet("alert_ids", emptySet()) ?: emptySet()
    @Synchronized fun addAlertId(ctx: Context, id: String) {
        val s = alertIds(ctx).toMutableSet(); s.add(id)
        prefs(ctx).edit().putStringSet("alert_ids", s.toList().takeLast(100).toSet()).apply()
    }

    // ---------- trasa (zaszyfrowana) ----------

    @Synchronized fun trail(ctx: Context): Trail =
        prefs(ctx).getString("trail_enc", null)?.let { Secret.decrypt(it) }?.let { Trail.decode(it) } ?: Trail()

    @Synchronized fun saveTrail(ctx: Context, t: Trail) {
        prefs(ctx).edit().putString("trail_enc", Secret.encrypt(t.encode())).apply()
    }

    @Synchronized fun clearTrail(ctx: Context) { prefs(ctx).edit().remove("trail_enc").apply() }

    // ---------- eksport / import (bez klucza AI, trasy i alarmów) ----------

    fun exportJson(ctx: Context): String = JSONObject()
        .put("czuj", 1)
        .put("scan_mode", scanMode(ctx))
        .put("voice_on", voiceOn(ctx))
        .put("voice_rate", voiceRate(ctx).toDouble())
        .put("share", share(ctx))
        .put("diag_share", diagShare(ctx))
        .put("mine", mineObj(ctx))
        .toString()

    /** false = to nie plik ustawień Czuja. */
    fun importJson(ctx: Context, s: String): Boolean {
        val o = try { JSONObject(s) } catch (e: Exception) { return false }
        if (o.optInt("czuj") != 1) return false
        val e = prefs(ctx).edit()
        e.putInt("scan_mode", o.optInt("scan_mode", 0).coerceIn(0, 2))
        e.putBoolean("voice_on", o.optBoolean("voice_on", true))
        e.putFloat("voice_rate", o.optDouble("voice_rate", 1.0).toFloat().coerceIn(0.5f, 2f))
        e.putBoolean("share", o.optBoolean("share", false))
        e.putBoolean("diag_share", o.optBoolean("diag_share", false))
        o.optJSONObject("mine")?.let { e.putString("mine", it.toString()) }
        e.apply()
        return true
    }
}
