package com.asior.czuj

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

// Głos systemowy Androida (polski). Brak polskiego głosu = funkcja niedostępna.
object Voice : TextToSpeech.OnInitListener {
    @Volatile var ready = false
        private set
    @Volatile var failed = false
        private set
    private var tts: TextToSpeech? = null
    private val pending = ArrayList<String>()

    @Synchronized fun init(ctx: Context) {
        if (tts == null) tts = TextToSpeech(ctx.applicationContext, this)
    }

    @Synchronized override fun onInit(status: Int) {
        val t = tts
        if (status == TextToSpeech.SUCCESS && t != null) {
            val r = t.setLanguage(Locale("pl", "PL"))
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
        failed = !ready
        if (ready) pending.forEach { speakNow(it) }
        pending.clear()
    }

    @Synchronized fun say(text: String) {
        if (ready) speakNow(text) else if (!failed) pending.add(text)
    }

    private fun speakNow(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "czuj" + System.nanoTime())
    }
}
