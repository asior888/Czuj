package com.asior.czuj

// Sterowanie aplikacją głosem lub tekstem, BEZ internetu i bez modelu AI.
// Czysty Kotlin: testowany w app/src/test. null = to nie komenda (idzie do asystenta).
object Commands {
    sealed class Cmd {
        object Status : Cmd()
        object Scan : Cmd()
        object GuardOn : Cmd()
        object GuardOff : Cmd()
        object ShowMap : Cmd()
        object ShowSettings : Cmd()
        object ShowGuard : Cmd()
        object Alerts : Cmd()
        object Unmute : Cmd()
        object Mine : Cmd()
        object ShareOn : Cmd()
        object ShareOff : Cmd()
        object Help : Cmd()
        object Diag : Cmd()
        object Support : Cmd()
        data class Mute(val minutes: Int) : Cmd()
    }

    const val HELP = "Powiedz na przykład: czy ktoś mnie śledzi, skanuj, pokaż mapę, ustawienia, " +
        "wycisz na godzinę, włącz alarmy, to mój tracker, włącz albo wyłącz strażnika, " +
        "włącz albo wyłącz udostępnianie, diagnostyka."

    private val FOLD = mapOf('ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n',
        'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z')

    fun norm(s: String): String =
        s.lowercase().map { FOLD[it] ?: it }.joinToString("")
            .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Usuwa słowo wywołujące z początku („hej czuj, skanuj” -> „skanuj”). */
    fun stripWake(text: String, wake: String): String {
        val t = norm(text)
        val w = norm(wake)
        if (w.isNotEmpty() && t.startsWith(w)) return t.removePrefix(w).trim()
        return t
    }

    private val NUM = mapOf("jedna" to 1, "jeden" to 1, "dwie" to 2, "dwa" to 2,
        "trzy" to 3, "cztery" to 4, "piec" to 5, "szesc" to 6, "osiem" to 8, "dziesiec" to 10,
        "pietnascie" to 15, "dwadziescia" to 20, "trzydziesci" to 30, "czterdziesci" to 40, "piecdziesiat" to 50)

    /** Ile minut: „na godzinę” 60, „na 2 godziny” 120, „na 30 minut” 30, „na pół godziny” 30, samo „wycisz” 60. */
    fun minutes(t: String): Int {
        val words = t.split(" ")
        var n: Int? = null
        for (w in words) { w.toIntOrNull()?.let { n = it }; NUM[w]?.let { n = it } }
        val m = when {
            t.contains("pol godziny") -> 30
            Regex("\\bgodzin").containsMatchIn(t) -> (n ?: 1) * 60
            Regex("\\bminut").containsMatchIn(t) -> n ?: 30
            Regex("\\bdob|\\bdzien|\\bdnia\\b").containsMatchIn(t) -> (n ?: 1) * 1440
            else -> 60
        }
        return m.coerceIn(5, 1440)
    }

    fun parse(raw: String, wake: String = ""): Cmd? {
        val t = stripWake(raw, wake)
        if (t.isEmpty()) return null
        fun has(vararg p: String) = p.any { Regex(it).containsMatchIn(t) }
        val off = has("\\bwylacz", "\\bzatrzymaj", "\\bstop\\b")
        val on = has("\\bwlacz", "\\buruchom", "\\bstart")
        return when {
            has("\\bpomoc", "co umiesz", "co potrafisz", "jakie komendy") -> Cmd.Help
            has("\\bodcisz", "wlacz alarm", "wlacz powiadomien") -> Cmd.Unmute
            has("\\bwycisz", "\\bcisza\\b", "nie przeszkadzaj") -> Cmd.Mute(minutes(t))
            has("udostepn", "wspoln") && off -> Cmd.ShareOff
            has("udostepn", "wspoln") && on -> Cmd.ShareOn
            has("straznik", "pilnowan") && off -> Cmd.GuardOff
            has("straznik", "pilnowan") && on -> Cmd.GuardOn
            has("\\bpilnuj") -> Cmd.GuardOn
            has("to moj", "to moje", "moj tracker", "moj airtag", "moj tile", "moje urzadzenie") -> Cmd.Mine
            has("\\bmap") -> Cmd.ShowMap
            has("ustawien") -> Cmd.ShowSettings
            has("diagnost", "raport bledow") -> Cmd.Diag
            has("wesprz", "wsparci", "darowizn", "postaw kaw") -> Cmd.Support
            has("\\bskanuj", "\\bsprawdz okolic", "przeskanuj", "szukaj tracker") -> Cmd.Scan
            has("\\balarm", "ostatni alarm") -> Cmd.Alerts
            has("sledz", "jest bezpiecznie", "\\bstan\\b", "co slychac", "jakis tracker", "ktos za mna", "obserwuje") -> Cmd.Status
            has("\\bstraznik", "ekran glowny") -> Cmd.ShowGuard
            else -> null
        }
    }
}
