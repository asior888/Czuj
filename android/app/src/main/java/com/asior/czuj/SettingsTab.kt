package com.asior.czuj

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

// Zakładka Ustawienia: wszystko w jednym przewijanym ekranie, od najczęściej używanych.
class SettingsTab(private val act: MainActivity, private val ui: Ui) {
    private val col = ui.column()
    val view = ScrollView(act).apply { addView(col) }

    fun build() {
        col.removeAllViews()
        col.addView(ui.text("Ustawienia", 26f, C.TXT, bold = true).apply { setPadding(0, ui.dp(8), 0, 0) })

        // ---------- strażnik ----------
        val modes = listOf("najmniej baterii, wykrywa w ciągu kilku minut",
            "złoty środek", "najszybciej wykrywa, zużywa najwięcej baterii")
        col.addView(ui.card(
            ui.label("Tryb skanowania"),
            ui.choice(Store.SCAN_MODES, Store.scanMode(act)) { Store.setScanMode(act, it); GuardService.rescan(act); build() },
            ui.text(modes[Store.scanMode(act)], 13.5f, C.DIM).apply { setPadding(0, ui.dp(8), 0, 0) },
        ))

        // ---------- głos ----------
        col.addView(ui.card(
            ui.label("Głos"),
            ui.toggle("Czytaj alarmy na głos", "Powiadomienie przychodzi zawsze", Store.voiceOn(act)) { Store.setVoiceOn(act, it) },
            ui.text("Tempo mowy", 15.5f).apply { setPadding(0, ui.dp(8), 0, 0) },
            ui.choice(listOf("wolno", "normalnie", "szybko"), Store.RATES.indexOf(Store.voiceRate(act)).coerceAtLeast(1)) {
                Store.setVoiceRate(act, Store.RATES[it]); Voice.rate = Store.RATES[it]; Voice.say("Tak mówię teraz."); build()
            },
            ui.text("Komendy głosowe działają bez internetu: " + Commands.HELP, 13f, C.DIM).apply { setPadding(0, ui.dp(10), 0, 0) },
        ))

        // ---------- moje urządzenia ----------
        val mine = Store.mine(act)
        val mineCard = ui.card(ui.label("Moje urządzenia (bez alarmów)"))
        if (mine.isEmpty()) mineCard.addView(ui.text("Pusto. Gdy alarm dotyczy twojego trackera, stuknij „To moje” w powiadomieniu albo powiedz „to mój tracker”.", 14f, C.DIM))
        for ((addr, desc) in mine) {
            mineCard.addView(ui.text(desc, 15f).apply { setPadding(0, ui.dp(8), 0, 0) })
            mineCard.addView(ui.button("Usuń z moich") { Store.removeMine(act, addr); build() })
        }
        mineCard.addView(ui.text("AirTag bez iPhone'a zmienia adres mniej więcej raz na dobę, więc czasem trzeba go oznaczyć ponownie.", 13f, C.DIM).apply { setPadding(0, ui.dp(8), 0, 0) })
        col.addView(mineCard)

        // ---------- wspólna mapa ----------
        col.addView(ui.card(
            ui.label("Wspólna mapa"),
            ui.toggle("Udostępniaj alarmy anonimowo",
                "Wysyłam tylko typ trackera, dzień i kratkę ok. 2 km. Bez adresów urządzeń, godziny i dokładnego miejsca.",
                Store.share(act)) { Store.setShare(act, it) },
            ui.text(if (Cloud.configured(act)) "Serwer: działa" else "Serwer: jeszcze nie ustawiony (mapa okolicy pusta)", 13f, C.DIM),
            ui.button("Nowy anonimowy numer telefonu") { Store.resetInstallId(act); act.toast("Nowy numer ustawiony.") },
        ))

        // ---------- asystent AI (opcja) ----------
        val key = EditText(act).apply {
            hint = if (Store.key(act).isEmpty()) "Klucz Anthropic (opcjonalnie)" else "Klucz zapisany (zaszyfrowany)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setTextColor(C.TXT); setHintTextColor(C.DIM); textSize = 15f
            background = ui.round(C.BG, 12, C.LINE)
            setPadding(ui.dp(14), 0, ui.dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(50)).apply { topMargin = ui.dp(10) }
        }
        col.addView(ui.card(
            ui.label("Asystent AI (opcja)"),
            ui.text("Bez klucza Czuj odpowiada na komendy i pytania o stan. Z kluczem rozmawia swobodnie (płatne API).", 13.5f, C.DIM),
            key,
            ui.button("Zapisz klucz (pusty = usuń)") { Store.setKey(act, key.text.toString()); build(); act.toast("Zapisane.") },
        ))

        // ---------- diagnostyka ----------
        col.addView(ui.card(
            ui.label("Diagnostyka"),
            ui.text(act.caps().joinToString("\n"), 14f),
            ui.button("Kopiuj raport") { act.copyReport() },
            ui.text("Raport zostaje w telefonie. Wysyłasz go komuś tylko ty, bez adresów urządzeń i miejsc.", 13f, C.DIM),
            ui.toggle("Anonimowe liczniki", "Tylko: wersja aplikacji, wersja Androida, „start” albo nazwa awarii. Bez numeru telefonu.",
                Store.diagShare(act)) { Store.setDiagShare(act, it) },
        ))

        // ---------- kopia i dowody ----------
        col.addView(ui.card(
            ui.label("Kopia ustawień"),
            ui.text("Plik zaszyfrowany twoim hasłem, do przeniesienia na nowy telefon. Bez kont i logowania.", 13.5f, C.DIM),
            ui.button("Zapisz ustawienia do pliku") { act.exportSettings() },
            ui.button("Wczytaj ustawienia z pliku") { act.importSettings() },
            ui.label("Dowody").apply { setPadding(0, ui.dp(14), 0, 0) },
            ui.text("Trasa trackerów z 7 dni (czas, miejsce) jako plik CSV, np. dla policji. Tylko z tego telefonu.", 13.5f, C.DIM),
            ui.button("Zapisz trasę (CSV)") { act.exportTrail() },
            ui.button("Usuń trasę") { Store.clearTrail(act); act.toast("Trasa usunięta.") },
        ))

        // ---------- o aplikacji ----------
        col.addView(ui.card(
            ui.label("O aplikacji"),
            ui.text("Czuj ${act.packageManager.getPackageInfo(act.packageName, 0).versionName}", 15f),
            ui.button("Sprawdź aktualizacje") { act.checkUpdates(true) },
            ui.text("Czuj jest darmowy i bez reklam. Jeśli chcesz, możesz wesprzeć jego rozwój.", 13.5f, C.DIM).apply { setPadding(0, ui.dp(12), 0, 0) },
            ui.button("Wesprzyj Czuj ☕", primary = true) { act.run(Commands.Cmd.Support) },
        ))
    }
}
