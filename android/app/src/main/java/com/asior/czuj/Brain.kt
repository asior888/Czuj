package com.asior.czuj

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// Asystent: Anthropic Messages API. Wołać poza wątkiem UI.
object Brain {
    private const val MODEL = "claude-sonnet-5-5"
    private const val MAX_MESSAGES = 20
    private const val SYSTEM =
        "Jesteś Czuj, osobisty strażnik w kieszeni. Pilnujesz, czy za użytkownikiem " +
            "nie jeździ nieznany tracker Bluetooth (AirTag, Tile, SmartTag). " +
            "Odpowiadaj po polsku, maksymalnie 2 krótkie zdania, bez list i formatowania. " +
            "Nie wymyślaj wykryć: o trackerach mów tylko na podstawie stanu skanera. " +
            "Jeśli ktoś czuje się zagrożony, poradź zadzwonić pod 112."

    private val history = ArrayList<JSONObject>()

    @Synchronized fun ask(key: String, text: String, status: String): String {
        history.add(JSONObject().put("role", "user").put("content", text))
        while (history.size > MAX_MESSAGES) { history.removeAt(0); history.removeAt(0) }
        val body = JSONObject()
            .put("model", MODEL)
            .put("max_tokens", 300)
            .put("system", "$SYSTEM\nAktualny stan skanera: $status")
            .put("messages", JSONArray(history))
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.doOutput = true
            c.setRequestProperty("x-api-key", key)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.setRequestProperty("content-type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                history.removeAt(history.size - 1)
                throw IOException("API $code: ${txt.take(160)}")
            }
            val answer = JSONObject(txt).getJSONArray("content").getJSONObject(0).getString("text").trim()
            history.add(JSONObject().put("role", "assistant").put("content", answer))
            return answer
        } catch (e: IOException) {
            if (history.isNotEmpty() && history.last().optString("role") == "user") history.removeAt(history.size - 1)
            throw e
        } finally {
            c.disconnect()
        }
    }

    /** Odpowiedź bez internetu i bez klucza: tylko na podstawie skanera. */
    fun local(status: GuardService.Status, alerts: List<String>): String {
        if (!status.running) return "Strażnik jest wyłączony. Włącz go przyciskiem na ekranie."
        val last = alerts.lastOrNull()
        if (last != null) return "Ostatni alarm: $last"
        return "Nie widzę trackera, który by z tobą jechał. Trackerów w pobliżu: ${status.nearby}."
    }
}
