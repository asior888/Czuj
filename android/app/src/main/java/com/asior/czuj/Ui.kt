package com.asior.czuj

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

// Wygląd: ciemny „zmierzch” (bez czystej czerni i bieli), jeden spokojny akcent, przyciski >= 48 dp.
object C {
    const val BG = 0xFF131621.toInt()
    const val CARD = 0xFF1C2130.toInt()
    const val LINE = 0xFF2A3144.toInt()
    const val TXT = 0xFFD7DAE3.toInt()
    const val DIM = 0xFF8C93A8.toInt()
    const val ACC = 0xFF7FB8D9.toInt()
    const val ON_ACC = 0xFF102030.toInt()
    const val OK = 0xFF7FC8A9.toInt()
    const val WARN = 0xFFE8B86A.toInt()
    const val BAD = 0xFFE3907F.toInt()
}

/** Małe klocki widoków, wspólne dla zakładek. */
class Ui(val a: Activity) {
    fun dp(v: Int) = (v * a.resources.displayMetrics.density).toInt()
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    fun round(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat(); setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    fun text(s: String, size: Float = 15f, color: Int = C.TXT, bold: Boolean = false) = TextView(a).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(0f, 1.15f)
        layoutParams = lp()
    }

    fun label(s: String) = text(s.uppercase(), 12.5f, C.DIM, bold = true).apply {
        letterSpacing = 0.06f
        layoutParams = lp(4)
    }

    fun button(s: String, primary: Boolean = false, onClick: () -> Unit) = Button(a).apply {
        text = s; isAllCaps = false; textSize = 15.5f
        stateListAnimator = null
        setTextColor(if (primary) C.ON_ACC else C.TXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(50); minimumHeight = dp(50)
        background = if (primary) round(C.ACC, 14) else round(C.CARD, 14, C.LINE)
        setOnClickListener { onClick() }
        layoutParams = lp(10)
    }

    fun card(vararg views: View) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = round(C.CARD, 16)
        layoutParams = lp(14)
        views.forEach { addView(it) }
    }

    fun column(pad: Int = 16) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(8), dp(pad), dp(120))
    }

    /** Przełącznik z opisem: cały wiersz jest stukalny. */
    fun toggle(title: String, desc: String, on: Boolean, set: (Boolean) -> Unit): View {
        val sw = Switch(a).apply { isChecked = on; isClickable = false }
        val texts = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15.5f))
            if (desc.isNotEmpty()) addView(text(desc, 13f, C.DIM))
        }
        return LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(0, dp(6), 0, dp(6))
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { sw.isChecked = !sw.isChecked; set(sw.isChecked) }
            layoutParams = lp(4)
        }
    }

    /** Wybór jednej z kilku opcji (przyciski w rzędzie). */
    fun choice(options: List<String>, selected: Int, set: (Int) -> Unit): View {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(10) }
        options.forEachIndexed { i, o ->
            row.addView(Button(a).apply {
                text = o; isAllCaps = false; textSize = 14.5f; stateListAnimator = null
                minHeight = dp(48); minimumHeight = dp(48)
                setTextColor(if (i == selected) C.ON_ACC else C.TXT)
                background = if (i == selected) round(C.ACC, 12) else round(C.BG, 12, C.LINE)
                setOnClickListener { set(i) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
        return row
    }
}
