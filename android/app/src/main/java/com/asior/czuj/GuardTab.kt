package com.asior.czuj

import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Zakładka Strażnik: duże kółko ze stanem, włącznik, rozmowa, ostatnie alarmy.
class GuardTab(private val act: MainActivity, private val ui: Ui) {
    private val state = ui.text("", 30f, C.TXT, bold = true).apply { gravity = Gravity.CENTER }
    private val sub = ui.text("", 14.5f, C.DIM).apply { gravity = Gravity.CENTER }
    private val ring = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.CARD) }
    private val guardBtn = ui.button("", primary = true) { act.toggleGuard() }
    private val muteText = ui.text("", 14.5f, C.WARN)
    private val muteCard = ui.card(muteText, ui.button("Włącz alarmy z powrotem") { act.run(Commands.Cmd.Unmute) })
    private val answer = ui.text("Stuknij mikrofon i powiedz na przykład: „czy ktoś mnie śledzi?”", 15f)
    private val input = EditText(act).apply {
        hint = "albo napisz"
        setTextColor(C.TXT); setHintTextColor(C.DIM); textSize = 15.5f
        background = ui.round(C.BG, 12, C.LINE)
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        minHeight = ui.dp(50)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
    }
    private val alerts = ui.text("", 14.5f)
    private val mineBtn = ui.button("To mój tracker (ostatni alarm)") { act.run(Commands.Cmd.Mine) }

    val view: ScrollView

    init {
        val col = ui.column()
        col.addView(ui.text("Czuj", 26f, C.TXT, bold = true).apply { setPadding(0, ui.dp(8), 0, 0) })
        col.addView(ui.text("Strażnik w kieszeni", 14f, C.DIM))

        val circle = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ring
            addView(state); addView(sub)
        }
        col.addView(FrameLayout(act).apply {
            addView(circle, FrameLayout.LayoutParams(ui.dp(230), ui.dp(230), Gravity.CENTER))
            setPadding(0, ui.dp(22), 0, ui.dp(10))
        })
        col.addView(guardBtn)
        col.addView(muteCard)

        val send = ImageButton(act).apply {
            setImageResource(R.drawable.ic_send); setColorFilter(C.ON_ACC)
            background = ui.round(C.ACC, 12)
            setOnClickListener { submit() }
            contentDescription = "Wyślij"
        }
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, ui.dp(10), 0, 0)
            addView(input, LinearLayout.LayoutParams(0, ui.dp(50), 1f))
            addView(send, LinearLayout.LayoutParams(ui.dp(50), ui.dp(50)).apply { leftMargin = ui.dp(8) })
        }
        col.addView(ui.card(ui.label("Rozmowa"), answer, row))
        col.addView(ui.card(ui.label("Ostatnie alarmy"), alerts, mineBtn,
            ui.button("Wyczyść alarmy") { act.clearAlerts() }))
        view = ScrollView(act).apply { isFillViewport = true; addView(col) }
    }

    private fun submit() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        act.handleText(t, voice = false)
    }

    fun show(q: String, a: String) { answer.text = if (q.isEmpty()) a else "Ty: $q\nCzuj: $a" }

    fun refresh() {
        val s = GuardService.status()
        val list = Store.alerts(act)
        val recent = list.lastOrNull()?.let { ageMin(it) }?.let { it in 0..360 } == true
        when {
            !s.running -> { state.text = "Wyłączony"; state.setTextColor(C.DIM); sub.text = "nie pilnuję"; ring.setStroke(ui.dp(3), C.LINE) }
            recent -> { state.text = "Uwaga"; state.setTextColor(C.WARN); sub.text = "alarm w ostatnich 6 h"; ring.setStroke(ui.dp(3), C.WARN) }
            else -> { state.text = "Spokojnie"; state.setTextColor(C.OK); sub.text = "trackerów w pobliżu: ${s.nearby}"; ring.setStroke(ui.dp(3), C.OK) }
        }
        if (s.running && s.error.isNotEmpty()) sub.text = s.error
        guardBtn.text = if (s.running) "Wyłącz strażnika" else "Włącz strażnika"
        val muted = Store.muted(act)
        muteCard.visibility = if (muted) android.view.View.VISIBLE else android.view.View.GONE
        if (muted) muteText.text = "Alarmy wyciszone do " +
            SimpleDateFormat("HH:mm", Locale.ROOT).format(Date(Store.muteUntil(act))) + ". Nadal zapisuję, co widzę."
        alerts.text = if (list.isEmpty()) "Brak alarmów." else list.takeLast(5).reversed().joinToString("\n\n")
        mineBtn.visibility = if (GuardService.latestAlert != null) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun ageMin(line: String): Long? = try {
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse(line.take(16))!!.time
        (System.currentTimeMillis() - t) / 60_000
    } catch (e: Exception) { null }
}
