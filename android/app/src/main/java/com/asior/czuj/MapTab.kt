package com.asior.czuj

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

// Zakładka Mapa: strona assets/map/index.html (MapLibre). Strona NIE ma mostu do aplikacji:
// aplikacja tylko wkłada dane przez evaluateJavascript. Trasa idzie z telefonu, okolica z serwera.
@SuppressLint("SetJavaScriptEnabled")
class MapTab(private val act: MainActivity) {
    private var ready = false
    val view = WebView(act).apply {
        setBackgroundColor(C.BG)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowContentAccess = false
        settings.setGeolocationEnabled(false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true // żadnych przejść na obce strony
            override fun onPageFinished(v: WebView, url: String) { ready = true; load() }
        }
        loadUrl("file:///android_asset/map/index.html")
    }

    fun load() {
        if (!ready) return
        val trail = Store.trail(act).toJson(Store.alertIds(act))
        Where.locate(act) { loc ->
            Thread {
                val d = JSONObject()
                d.put("trail", org.json.JSONArray(trail))
                if (loc != null) d.put("me", JSONObject().put("lat", loc.latitude).put("lon", loc.longitude))
                d.put("server", Cloud.configured(act))
                try {
                    Cloud.stats(act)?.let { s ->
                        d.put("cells", s.optJSONObject("cells") ?: JSONObject())
                        d.put("from", s.optString("from")); d.put("to", s.optString("to"))
                    }
                } catch (e: Exception) {
                    Diag.error(act, "mapa okolicy", e)
                }
                act.runOnUiThread { view.evaluateJavascript("window.czujShow && czujShow($d)", null) }
            }.start()
        }
    }
}
