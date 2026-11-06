package com.asior.czuj

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.asior.czuj.Commands.Cmd
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Ekran główny: 3 zakładki (Strażnik, Mapa, Ustawienia) + pływający mikrofon.
// Głos i tekst idą tą samą drogą: najpierw komendy (offline), potem asystent AI, a bez niego odpowiedź ze skanera.
class MainActivity : Activity() {
    private val ui = Ui(this)
    private lateinit var guard: GuardTab
    private lateinit var settings: SettingsTab
    private lateinit var content: FrameLayout
    private lateinit var banner: TextView
    private lateinit var block: LinearLayout
    private val tabs = ArrayList<Pair<ImageView, TextView>>()
    private var mapTab: MapTab? = null
    private var current = 0
    private var pendingExport = ""
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { if (current == 0) guard.refresh(); h.postDelayed(this, 2000) }
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_SPEECH = 2
        private const val REQ_EXPORT = 10
        private const val REQ_IMPORT = 11
        private const val REQ_TRAIL = 12
        const val WAKE = "hej czuj"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diag.install(this)
        Voice.init(this)
        Voice.rate = Store.voiceRate(this)
        // ekran z alarmami i kluczem nie trafia do zrzutów ani podglądu ostatnich aplikacji
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        guard = GuardTab(this, ui)
        settings = SettingsTab(this, ui)

        banner = ui.text("Jest nowa wersja Czuja. Stuknij, żeby pobrać.", 14.5f, C.ON_ACC, bold = true).apply {
            setBackgroundColor(C.WARN); gravity = Gravity.CENTER
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); visibility = View.GONE
            setOnClickListener { Updates.cached(this@MainActivity)?.let { openUrl(it.download) } }
        }
        content = FrameLayout(this)
        content.addView(guard.view)
        content.addView(settings.view.apply { visibility = View.GONE })

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(C.CARD)
            listOf(R.drawable.ic_tab_guard to "Strażnik", R.drawable.ic_tab_map to "Mapa", R.drawable.ic_tab_settings to "Ustawienia")
                .forEachIndexed { i, (icon, name) -> addView(navItem(i, icon, name), LinearLayout.LayoutParams(0, ui.dp(64), 1f)) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(banner)
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(nav)
        }
        val mic = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic); setColorFilter(C.ON_ACC)
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(C.ACC) }
            elevation = ui.dp(6).toFloat()
            contentDescription = "Mów do Czuja"
            setOnClickListener { listen() }
        }
        block = blockScreen()
        val root = FrameLayout(this).apply {
            setBackgroundColor(C.BG)
            addView(column)
            addView(mic, FrameLayout.LayoutParams(ui.dp(64), ui.dp(64), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = ui.dp(20); bottomMargin = ui.dp(84)
            })
            addView(block)
        }
        // Android 15+: aplikacja rysuje pod paskami systemu, więc odsuwamy treść o ich wysokość
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            }
            ins
        }
        setContentView(root)
        select(0)
        applyUpdate()
        checkUpdates(false)
        Diag.onStart(this)
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
        if (current == 2) settings.build()
    }

    override fun onPause() { h.removeCallbacks(tick); super.onPause() }

    // ---------- nawigacja ----------

    private fun navItem(i: Int, icon: Int, name: String): View {
        val img = ImageView(this).apply { setImageResource(icon) }
        val t = ui.text(name, 12.5f, C.DIM).apply { gravity = Gravity.CENTER }
        tabs.add(img to t)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(img, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
            addView(t)
            setOnClickListener { select(i) }
            contentDescription = name
        }
    }

    fun select(i: Int) {
        current = i
        tabs.forEachIndexed { j, (img, t) ->
            val c = if (j == i) C.ACC else C.DIM
            img.setColorFilter(c); t.setTextColor(c)
        }
        guard.view.visibility = if (i == 0) View.VISIBLE else View.GONE
        settings.view.visibility = if (i == 2) View.VISIBLE else View.GONE
        if (i == 1 && mapTab == null) mapTab = MapTab(this).also { content.addView(it.view) }
        mapTab?.view?.visibility = if (i == 1) View.VISIBLE else View.GONE
        when (i) {
            0 -> guard.refresh()
            1 -> mapTab?.load()
            2 -> settings.build()
        }
    }

    @Deprecated("onBackPressed")
    override fun onBackPressed() {
        if (current != 0) select(0) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------- aktualizacje ----------

    fun checkUpdates(force: Boolean) {
        Thread {
            val info = Updates.refresh(this, force)
            runOnUiThread {
                applyUpdate()
                if (force) toast(when {
                    info == null -> "Nie udało się sprawdzić (brak internetu albo wydania)."
                    info.latest > Updates.myCode(this) -> "Jest nowa wersja."
                    else -> "Masz najnowszą wersję."
                })
            }
        }.start()
    }

    private fun applyUpdate() {
        val st = Updates.state(Updates.myCode(this), Updates.cached(this))
        banner.visibility = if (st == Updates.State.BANNER) View.VISIBLE else View.GONE
        block.visibility = if (st == Updates.State.BLOCK) View.VISIBLE else View.GONE
        if (st == Updates.State.BLOCK) (block.getChildAt(1) as TextView).text =
            "W tej wersji jest błąd bezpieczeństwa. Pobierz nową, żeby dalej korzystać z Czuja." +
                (Updates.cached(this)?.reason?.takeIf { it.isNotEmpty() }?.let { "\n\n$it" } ?: "")
    }

    private fun blockScreen() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(C.BG)
        setPadding(ui.dp(28), 0, ui.dp(28), 0)
        isClickable = true
        visibility = View.GONE
        addView(ui.text("Potrzebna aktualizacja", 24f, C.WARN, bold = true).apply { gravity = Gravity.CENTER })
        addView(ui.text("", 15.5f).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(12), 0, ui.dp(8)) })
        addView(ui.button("Pobierz nową wersję", primary = true) { openUrl(Updates.cached(this@MainActivity)?.download ?: Updates.RELEASES) })
    }

    // ---------- strażnik ----------

    private fun neededPermissions(): List<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) p.add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        return p.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    fun toggleGuard() { if (GuardService.running) guardOff() else guardOn() }

    private fun guardOn() {
        val missing = neededPermissions()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS) else startGuard()
    }

    private fun guardOff() { GuardService.stop(this); h.postDelayed({ guard.refresh() }, 300) }

    private fun startGuard() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            guard.show("", "Bez zgody na lokalizację Android nie pozwala skanować Bluetooth w tle.")
            return
        }
        GuardService.start(this)
        h.postDelayed({ guard.refresh() }, 500)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_PERMS) startGuard()
    }

    fun clearAlerts() { Store.clearAlerts(this); GuardService.latestAlert = null; guard.refresh() }

    // ---------- głos i komendy ----------

    private fun listen() {
        Voice.stop()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pl-PL")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Mów do Czuja")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            select(0); guard.show("", "Rozpoznawanie mowy niedostępne na tym telefonie. Napisz pytanie.")
        }
    }

    fun handleText(text: String, voice: Boolean) {
        val cmd = Commands.parse(text, WAKE)
        if (cmd != null) {
            val a = run(cmd)
            if (current == 0) guard.show(text, a) else toast(a)
            if (voice) Voice.say(a)
            return
        }
        select(0)
        guard.show(text, "…")
        val key = Store.key(this)
        val status = GuardService.status()
        val alerts = Store.alerts(this)
        Thread {
            val a = if (key.isEmpty()) Brain.local(status, alerts) + " Powiedz „pomoc”, żeby usłyszeć komendy."
            else try {
                Brain.ask(key, text, status.forModel() + ", ostatnie alarmy: " + (alerts.takeLast(3).joinToString("; ").ifEmpty { "brak" }))
            } catch (e: Exception) {
                Diag.error(this, "asystent", e)
                Brain.local(status, alerts) + " (asystent offline)"
            }
            runOnUiThread { guard.show(text, a); if (voice) Voice.say(a.substringBefore(" (asystent offline")) }
        }.start()
    }

    /** Wykonuje komendę i zwraca krótką odpowiedź (do pokazania i przeczytania). */
    fun run(cmd: Cmd): String {
        val s = GuardService.status()
        val r = when (cmd) {
            Cmd.Status -> Brain.local(s, Store.alerts(this)) + if (Store.muted(this)) " Alarmy są wyciszone." else ""
            Cmd.Scan -> if (!s.running) "Strażnik jest wyłączony. Powiedz „włącz strażnika”."
                else { GuardService.rescan(this); "Skanuję od nowa. Trackerów w pobliżu teraz: ${s.nearby}." }
            Cmd.GuardOn -> if (s.running) "Już pilnuję." else { guardOn(); "Włączam strażnika." }
            Cmd.GuardOff -> if (!s.running) "Strażnik już jest wyłączony." else { guardOff(); "Wyłączam strażnika." }
            Cmd.ShowMap -> { select(1); "Mapa." }
            Cmd.ShowSettings -> { select(2); "Ustawienia." }
            Cmd.ShowGuard -> { select(0); "Strażnik." }
            Cmd.Alerts -> Store.alerts(this).lastOrNull()?.let { "Ostatni alarm: $it" } ?: "Nie było alarmów."
            is Cmd.Mute -> { Store.setMute(this, cmd.minutes); "Wyciszam alarmy na ${mins(cmd.minutes)}. Nadal zapisuję, co widzę." }
            Cmd.Unmute -> { Store.setMute(this, 0); "Alarmy znów włączone." }
            Cmd.Mine -> (GuardService.latestAlert ?: GuardService.latest)?.let { (addr, tag) ->
                GuardService.markMine(this, addr, tag); "Dobrze, $tag jest twój. Nie będę o nim ostrzegać."
            } ?: "Nie widzę teraz żadnego trackera, który mógłbym oznaczyć."
            Cmd.ShareOn -> { Store.setShare(this, true); "Udostępniam alarmy anonimowo na wspólną mapę." }
            Cmd.ShareOff -> { Store.setShare(this, false); "Nie udostępniam alarmów." }
            Cmd.Help -> Commands.HELP
            Cmd.Diag -> { select(2); "Diagnostyka jest w ustawieniach. Przycisk „Kopiuj raport” kopiuje ją do schowka." }
            Cmd.Support -> { openUrl(Updates.cached(this)?.support ?: Updates.SUPPORT); "Dzięki! Otwieram stronę wsparcia." }
        }
        if (current == 0) guard.refresh()
        return r
    }

    private fun mins(m: Int) = when {
        m % 1440 == 0 -> if (m == 1440) "dobę" else "${m / 1440} doby"
        m % 60 == 0 -> if (m == 60) "godzinę" else "${m / 60} godziny"
        else -> "$m minut"
    }

    // ---------- diagnostyka ----------

    fun caps(): List<String> {
        val out = ArrayList<String>()
        fun put(ok: Boolean, name: String, why: String = "") =
            out.add((if (ok) "✅ " else "❌ ") + name + (if (!ok && why.isNotEmpty()) ": $why" else ""))
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val ble = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        put(ble && adapter != null, "Bluetooth LE", "telefon go nie ma")
        if (ble && adapter != null) put(adapter.isEnabled, "Bluetooth włączony", "włącz w ustawieniach")
        val lm = getSystemService(LocationManager::class.java)
        val locOn = if (Build.VERSION.SDK_INT >= 28) lm?.isLocationEnabled == true else true
        put(locOn, "Lokalizacja", "włącz, bez niej alarmy nie zadziałają")
        put(neededPermissions().isEmpty(), "Zgody", "stuknij Włącz strażnika")
        put(SpeechRecognizer.isRecognitionAvailable(this), "Rozpoznawanie mowy", "pisz polecenia")
        put(Voice.ready, "Głos po polsku", if (Voice.failed) "doinstaluj polski głos w ustawieniach syntezy mowy" else "uruchamia się")
        put(Store.key(this).isNotEmpty(), "Asystent AI", "opcja; bez klucza działają komendy")
        put(Cloud.configured(this), "Wspólna mapa", "serwer jeszcze nie ustawiony")
        val s = GuardService.status()
        if (s.error.isNotEmpty()) out.add("⚠️ " + s.error)
        if (GuardService.cloudError.isNotEmpty()) out.add("⚠️ mapa: " + GuardService.cloudError)
        return out
    }

    fun copyReport() {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Czuj raport", Diag.report(this, caps())))
        toast("Raport skopiowany. Wklej go, komu chcesz.")
    }

    // ---------- pliki: kopia ustawień i trasa ----------

    private fun askPassword(title: String, done: (String) -> Unit) {
        val f = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "hasło, co najmniej ${Backup.MIN_PASS} znaków"
            setTextColor(C.TXT); setHintTextColor(C.DIM)
        }
        AlertDialog.Builder(this).setTitle(title).setView(FrameLayout(this).apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(f)
        }).setPositiveButton("OK") { _, _ ->
            val p = f.text.toString()
            if (p.length < Backup.MIN_PASS) toast("Hasło za krótkie.") else done(p)
        }.setNegativeButton("Anuluj", null).show()
    }

    fun exportSettings() = askPassword("Hasło do pliku (zapamiętaj je)") { p ->
        pendingExport = p
        create(REQ_EXPORT, "czuj-ustawienia.czuj", "application/octet-stream")
    }

    fun importSettings() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_IMPORT)
    }

    fun exportTrail() {
        if (Store.trail(this).points.isEmpty()) { toast("Trasa jest pusta."); return }
        create(REQ_TRAIL, "czuj-trasa.csv", "text/csv")
    }

    private fun create(req: Int, name: String, type: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type).putExtra(Intent.EXTRA_TITLE, name)
        @Suppress("DEPRECATION") startActivityForResult(i, req)
    }

    private fun trailCsv(): String {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        val sb = StringBuilder("czas;tracker;id;szerokosc;dlugosc\n")
        for (p in Store.trail(this).points) sb.append("${f.format(Date(p.t))};${p.tag};${p.id};${p.lat};${p.lon}\n")
        return sb.toString()
    }

    @Deprecated("startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { pendingExport = ""; return }
        when (requestCode) {
            REQ_SPEECH -> data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { handleText(it, voice = true) }
            REQ_EXPORT -> data?.data?.let { uri -> val p = pendingExport; pendingExport = ""; background("Zapisane.") {
                write(uri, Backup.seal(Store.exportJson(this).toByteArray(), p)) } }
            REQ_IMPORT -> data?.data?.let { uri -> askPassword("Hasło do pliku") { p -> background("Ustawienia wczytane.") {
                val raw = contentResolver.openInputStream(uri)?.use { it.readBytes().take(65536).toByteArray() } ?: throw Exception("nie da się otworzyć")
                val plain = Backup.open(raw, p) ?: throw Exception("złe hasło albo to nie plik Czuja")
                if (!Store.importJson(this, String(plain))) throw Exception("to nie plik ustawień Czuja")
                runOnUiThread { Voice.rate = Store.voiceRate(this); GuardService.rescan(this); settings.build() }
            } } }
            REQ_TRAIL -> data?.data?.let { uri -> background("Trasa zapisana.") { write(uri, trailCsv().toByteArray()) } }
        }
    }

    private fun write(uri: Uri, bytes: ByteArray) {
        contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw Exception("nie da się zapisać")
    }

    private fun background(ok: String, work: () -> Unit) {
        Thread {
            val r = try { work(); ok } catch (e: Exception) { "Nie wyszło: ${e.message}" }
            runOnUiThread { toast(r) }
        }.start()
    }

    // ---------- drobne ----------

    fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: ActivityNotFoundException) { toast("Brak przeglądarki.") }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() { mapTab?.view?.let { (it as? WebView)?.destroy() }; super.onDestroy() }
}
