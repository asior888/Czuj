package com.asior.czuj

import java.util.Locale

// Trasa trackera: gdzie widziałem ten sam podejrzany tracker przy tobie. TYLKO w telefonie
// (zaszyfrowana w Store), nigdy nie idzie do serwera. Służy do mapy i jako dowód (np. na policję).
// Czysty Kotlin: testowany w app/src/test.
class Trail {
    class Point(val id: String, val tag: String, val lat: Double, val lon: Double, val t: Long)

    companion object {
        const val EVERY_MS = 5 * 60_000L        // najwyżej 1 punkt na tracker co 5 min
        const val KEEP_MS = 7 * 24 * 3600_000L  // 7 dni
        const val MAX = 2000

        fun decode(s: String): Trail {
            val tr = Trail()
            for (line in s.split('\n')) {
                val p = line.split('|')
                if (p.size != 5) continue
                val lat = p[2].toDoubleOrNull() ?: continue
                val lon = p[3].toDoubleOrNull() ?: continue
                val t = p[4].toLongOrNull() ?: continue
                tr.points.add(Point(p[0], p[1], lat, lon, t))
            }
            return tr
        }
    }

    val points = ArrayList<Point>()

    /** true = dodany (minęło 5 min od ostatniego punktu tego trackera). */
    fun add(id: String, tag: String, lat: Double, lon: Double, now: Long): Boolean {
        val last = points.lastOrNull { it.id == id }
        if (last != null && now - last.t < EVERY_MS) return false
        points.add(Point(id.replace("|", ""), tag.replace("|", "").replace("\n", ""), lat, lon, now))
        prune(now)
        return true
    }

    fun due(id: String, now: Long): Boolean {
        val last = points.lastOrNull { it.id == id } ?: return true
        return now - last.t >= EVERY_MS
    }

    fun prune(now: Long) {
        points.removeAll { now - it.t > KEEP_MS }
        while (points.size > MAX) points.removeAt(0)
    }

    fun forget(id: String) { points.removeAll { it.id == id } }

    fun encode(): String = points.joinToString("\n") {
        String.format(Locale.ROOT, "%s|%s|%.5f|%.5f|%d", it.id, it.tag, it.lat, it.lon, it.t)
    }

    /** Dla mapy: [{id, tag, alert, pts:[[lon,lat,t],…]}], trackery od najnowszego. */
    fun toJson(alerted: Set<String>): String {
        val by = points.groupBy { it.id }.entries.sortedByDescending { e -> e.value.maxOf { it.t } }
        val sb = StringBuilder("[")
        by.forEachIndexed { i, (id, pts) ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(esc(id)).append("\",\"tag\":\"").append(esc(pts.last().tag))
                .append("\",\"alert\":").append(alerted.contains(id)).append(",\"pts\":[")
            pts.forEachIndexed { j, p ->
                if (j > 0) sb.append(',')
                sb.append(String.format(Locale.ROOT, "[%.5f,%.5f,%d]", p.lon, p.lat, p.t))
            }
            sb.append("]}")
        }
        return sb.append("]").toString()
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("<", "\\u003c").replace(Regex("[\\u0000-\\u001f]"), "")
}
