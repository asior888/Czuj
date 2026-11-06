package com.asior.czuj

import com.asior.czuj.Commands.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandsTest {
    private fun p(s: String, wake: String = "hej czuj") = Commands.parse(s, wake)

    @Test fun status() {
        assertEquals(Cmd.Status, p("Czy ktoś mnie śledzi?"))
        assertEquals(Cmd.Status, p("hej czuj, jest bezpiecznie?"))
        assertEquals(Cmd.Status, p("co słychać"))
    }

    @Test fun guardOnOff() {
        assertEquals(Cmd.GuardOn, p("włącz strażnika"))
        assertEquals(Cmd.GuardOff, p("Wyłącz strażnika"))
        assertEquals(Cmd.GuardOn, p("pilnuj mnie"))
    }

    @Test fun mute() {
        assertEquals(Cmd.Mute(60), p("wycisz na godzinę"))
        assertEquals(Cmd.Mute(120), p("wycisz na 2 godziny"))
        assertEquals(Cmd.Mute(120), p("wycisz na dwie godziny"))
        assertEquals(Cmd.Mute(30), p("wycisz na pół godziny"))
        assertEquals(Cmd.Mute(15), p("wycisz na 15 minut"))
        assertEquals(Cmd.Mute(60), p("wycisz"))
        assertEquals(Cmd.Mute(1440), p("wycisz na dobę"))
        assertEquals(Cmd.Unmute, p("włącz alarmy"))
        assertEquals(Cmd.Unmute, p("odcisz"))
    }

    @Test fun navigationAndOthers() {
        assertEquals(Cmd.ShowMap, p("pokaż mapę"))
        assertEquals(Cmd.ShowSettings, p("otwórz ustawienia"))
        assertEquals(Cmd.Scan, p("skanuj teraz"))
        assertEquals(Cmd.Mine, p("to mój tracker"))
        assertEquals(Cmd.ShareOn, p("włącz udostępnianie"))
        assertEquals(Cmd.ShareOff, p("wyłącz udostępnianie"))
        assertEquals(Cmd.Help, p("co umiesz"))
        assertEquals(Cmd.Diag, p("diagnostyka"))
        assertEquals(Cmd.Support, p("chcę wesprzeć"))
        assertEquals(Cmd.Alerts, p("ostatni alarm"))
    }

    @Test fun notACommand() {
        assertNull(p("jaka jest pogoda w Chojnicach"))
        assertNull(p("hej czuj"))
        assertNull(p(""))
    }
}
