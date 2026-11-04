package com.asior.czuj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerCoreTest {
    private val home = setOf("a", "b", "c", "d", "e", "f")
    private val away = setOf("u", "v", "w", "x", "y", "z")
    private val min = 60_000L

    @Test fun classify() {
        assertEquals("AirTag / Find My", TrackerCore.classify(mapOf(0x004C to byteArrayOf(0x12, 0x19, 0)), emptyList()))
        assertEquals("", TrackerCore.classify(mapOf(0x004C to byteArrayOf(0x10, 0x05)), emptyList()))
        assertEquals("Tile", TrackerCore.classify(emptyMap(), listOf("0000FEED-0000-1000-8000-00805f9b34fb")))
        assertEquals("SmartTag (Samsung)", TrackerCore.classify(emptyMap(), listOf("0000fd5a-0000-1000-8000-00805f9b34fb")))
    }

    @Test fun alarmWhenTrackerFollows() {
        val w = TrackerCore.Watch()
        val out = ArrayList<String>()
        var t = 0L
        for (p in listOf(home, home, away, away, away)) {
            w.sighting("AA", "AirTag / Find My", p, t)?.let { out.add(it) }
            t += 8 * min
        }
        assertEquals(1, out.size)
    }

    @Test fun noAlarmInOnePlace() {
        val w = TrackerCore.Watch()
        var t = 0L
        repeat(8) {
            assertNull(w.sighting("CC", "Tile", home, t))
            t += 8 * min
        }
    }

    @Test fun onlyOnceIn6h() {
        val w = TrackerCore.Watch()
        var t = 0L
        var n = 0
        for (p in listOf(home, home, away, away, away, away, away, away)) {
            if (w.sighting("EE", "Tile", p, t) != null) n++
            t += 8 * min
        }
        assertEquals(1, n)
    }

    @Test fun debounceIgnoresBurst() {
        val w = TrackerCore.Watch()
        repeat(10) { w.sighting("FF", "Tile", home, it * 1000L) }
        assertEquals(1, w.seen["FF"]!!.n)
    }
}
