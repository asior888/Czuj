package com.asior.czuj

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Jeden ekran: stan, włącznik strażnika, rozmowa (głos lub tekst), alarmy, klucz.
class MainActivity : Activity() {
    private val bg = Color.parseColor("#141722")
    private val card = Color.parseColor("#1E2333")
    private val fg = Color.parseColor("#D8DBE6")
    private val dim = Color.parseColor("#8A90A6")
    private val accent = Color.parseColor("#6FA8DC")

    private lateinit var statusView: TextView
    private lateinit var capsView: TextView
    private lateinit var guardBtn: Button
    private lateinit var answerView: TextView
    private lateinit var alertsView: TextView
    private lateinit var question: EditText
    private lateinit var keyField: EditText
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 2000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Voice.init(this)
        window.statusBarColor = bg
        // ekran z kluczem i alarmami nie trafia do zrzutów ani podglądu ostatnich aplikacji
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        root.addView(text("Czuj", 30f, fg, bold = true))
        root.addView(text("Strażnik w kieszeni", 15f, dim))

        statusView = text("", 17f, fg, bold = true)
        guardBtn = button("") { toggleGuard() }
        root.addView(section(statusView, guardBtn))

        answerView = text("Zapytaj na przykład: czy ktoś mnie śledzi?", 15f, fg)
        question = EditText(this).apply {
            hint = "Napisz pytanie"
            setTextColor(fg); setHintTextColor(dim)
        }
        root.addView(section(
            label("Rozmowa"), answerView,
            button("Zapytaj głosem") { talk() },
            question,
            button("Wyślij") { send(question.text.toString()) }
        ))

        alertsView = text("", 14f, fg)
        root.addView(section(label("Ostatnie alarmy"), alertsView))

        capsView = text("", 14f, fg)
        keyField = EditText(this).apply {
            hint = "Klucz Anthropic (pusty + Zapisz = usuń)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            // klawiatura nie uczy się klucza (brak podpowiedzi i zapisu w słowniku)
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setTextColor(fg); setHintTextColor(dim)
        }
        root.addView(section(
            label("Co działa na tym telefonie"), capsView,
            keyField,
            button("Zapisz klucz") {
                Store.setKey(this, keyField.text.toString())
                keyField.setText("")
                refresh()
            }
        ))

        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    // ---------- strażnik ----------

    private fun neededPermissions(): List<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) p.add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        return p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun toggleGuard() {
        if (GuardService.running) { GuardService.stop(this); refresh(); return }
        val missing = neededPermissions()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else startGuard()
    }

    private fun startGuard() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            answerView.text = "Bez zgody na lokalizację Android nie pozwala skanować w tle."
            return
        }
        GuardService.start(this)
        ui.postDelayed({ refresh() }, 500)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 1) startGuard()
    }

    // ---------- rozmowa ----------

    private fun talk() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Mów do Czuja")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, 2)
        } catch (e: ActivityNotFoundException) {
            answerView.text = "Rozpoznawanie mowy niedostępne na tym telefonie. Napisz pytanie."
        }
    }

    @Deprecated("startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 2 || resultCode != RESULT_OK) return
        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        send(heard)
    }

    private fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        question.setText("")
        answerView.text = "Ty: $text\n…"
        val key = Store.key(this)
        val status = GuardService.status()
        val alerts = Store.alerts(this)
        Thread {
            val answer = if (key.isEmpty()) {
                Brain.local(status, alerts)
            } else {
                try {
                    Brain.ask(key, text, status.forModel() + ", ostatnie alarmy: " +
                        (alerts.takeLast(3).joinToString("; ").ifEmpty { "brak" }))
                } catch (e: Exception) {
                    Brain.local(status, alerts) + " (asystent offline: ${e.message})"
                }
            }
            runOnUiThread {
                answerView.text = "Ty: $text\nCzuj: $answer"
                Voice.say(answer.substringBefore(" (asystent offline"))
            }
        }.start()
    }

    // ---------- stan ----------

    private fun refresh() {
        val s = GuardService.status()
        statusView.text = if (s.running) "Pilnuję. Trackerów w pobliżu: ${s.nearby}" else "Strażnik wyłączony"
        statusView.setTextColor(if (s.running) accent else fg)
        guardBtn.text = if (s.running) "Wyłącz strażnika" else "Włącz strażnika"
        val alerts = Store.alerts(this)
        alertsView.text = if (alerts.isEmpty()) "Brak alarmów." else alerts.takeLast(5).reversed().joinToString("\n\n")
        capsView.text = caps(s).joinToString("\n")
    }

    private fun caps(s: GuardService.Status): List<String> {
        val out = ArrayList<String>()
        fun put(ok: Boolean, name: String, why: String = "") = out.add((if (ok) "✅ " else "❌ ") + name + (if (!ok && why.isNotEmpty()) ": $why" else ""))
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val ble = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        put(ble && adapter != null, "Bluetooth LE", "telefon go nie ma")
        if (ble && adapter != null) put(adapter.isEnabled, "Bluetooth włączony", "włącz w ustawieniach")
        val lm = getSystemService(LocationManager::class.java)
        val locOn = if (Build.VERSION.SDK_INT >= 28) lm?.isLocationEnabled == true else true
        put(locOn, "Lokalizacja (odcisk miejsca z Wi-Fi)", "włącz, bez niej alarmy nie zadziałają")
        put(neededPermissions().isEmpty(), "Zgody", "stuknij Włącz strażnika")
        put(SpeechRecognizer.isRecognitionAvailable(this), "Rozpoznawanie mowy", "pisz pytania")
        put(Voice.ready, "Głos po polsku", if (Voice.failed) "doinstaluj polski głos w ustawieniach syntezy mowy" else "uruchamia się")
        put(Store.key(this).isNotEmpty(), "Asystent AI", "bez klucza odpowiadam tylko o stanie skanera")
        if (s.error.isNotEmpty()) out.add("⚠️ " + s.error)
        return out
    }

    // ---------- widoki ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun label(s: String) = text(s.uppercase(), 12f, dim, bold = true)

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
        minHeight = dp(48)
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(accent) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
    }

    private fun section(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.START
        setPadding(dp(16), dp(12), dp(16), dp(16))
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(card) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(16) }
        views.forEach { addView(it) }
    }
}
