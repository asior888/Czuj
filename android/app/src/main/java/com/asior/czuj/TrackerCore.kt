package com.asior.czuj

// Reguły trackerów, te same co linux/scanner.py (pochodzą z RfCore w VEL).
// Czysty Kotlin bez Androida: testowany w app/src/test.
object TrackerCore {
    const val MIN_SIGHTINGS = 4
    const val TRACK_MS = 25 * 60_000L
    const val MIN_PLACES = 2
    const val MIN_PER_PLACE = 2
    const val REPEAT_MS = 6 * 3600_000L
    const val PLACE_JACCARD = 0.34
    const val MAX_PLACES = 80
    const val MAX_DEVICES = 500
    const val DEBOUNCE_MS = 60_000L // jedna obserwacja tego samego trackera na minutę

    fun classify(mfr: Map<Int, ByteArray>, uuids: Collection<String>): String {
        val apple = mfr[0x004C]
        if (apple != null && apple.size >= 2 &&
            (apple[0].toInt() and 0xFF) == 0x12 && (apple[1].toInt() and 0xFF) == 0x19
        ) return "AirTag / Find My"
        for (raw in uuids) {
            val u = raw.lowercase()
            if (u.startsWith("0000feed") || u.startsWith("0000feec")) return "Tile"
            if (u.startsWith("0000fd5a")) return "SmartTag (Samsung)"
        }
        return ""
    }

    class Rec(var tag: String, var n: Int, val first: Long, var last: Long) {
        val places = HashMap<Int, Int>()
    }

    class Watch {
        val places = ArrayList<Set<String>>()
        val seen = LinkedHashMap<String, Rec>()
        val alerted = HashMap<String, Long>()

        fun placeOf(top: Set<String>): Int {
            if (top.isEmpty()) return -1
            for (i in places.indices) {
                val p = places[i]
                val union = (top + p).size
                if (union > 0 && (top intersect p).size.toDouble() / union >= PLACE_JACCARD) return i
            }
            if (places.size >= MAX_PLACES) return -1
            places.add(top.toSet())
            return places.size - 1
        }

        /** Jedna obserwacja trackera. Zwraca tekst alarmu albo null. */
        fun sighting(addr: String, tag: String, top: Set<String>, now: Long): String? {
            val prev = seen[addr]
            if (prev != null && now - prev.last < DEBOUNCE_MS) return null
            val place = placeOf(top)
            val rec = prev ?: Rec(tag, 0, now, now).also { seen[addr] = it }
            rec.n++
            rec.last = now
            rec.places[place] = (rec.places[place] ?: 0) + 1
            prune()
            val span = rec.last - rec.first
            val multi = rec.places.count { it.key >= 0 && it.value >= MIN_PER_PLACE }
            if (rec.n < MIN_SIGHTINGS || span < TRACK_MS || multi < MIN_PLACES) return null
            val last = alerted[addr]
            if (last != null && now - last < REPEAT_MS) return null
            alerted[addr] = now
            return "Uwaga: ${rec.tag} jest przy tobie od ${span / 60_000} minut, w $multi różnych miejscach. " +
                "Jeśli to nie twoje, sprawdź torbę, kurtkę i pojazd."
        }

        fun nearby(now: Long, windowMs: Long = 2 * 60_000L): Int =
            seen.values.count { now - it.last <= windowMs }

        private fun prune() {
            if (seen.size <= MAX_DEVICES) return
            val oldest = seen.entries.sortedBy { it.value.last }.take(seen.size - MAX_DEVICES).map { it.key }
            for (k in oldest) seen.remove(k)
        }
    }
}
