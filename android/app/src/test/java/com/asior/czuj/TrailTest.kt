package com.asior.czuj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailTest {
    @Test fun throttleAndKeep() {
        val t = Trail()
        assertTrue(t.add("A", "Tile", 53.7, 17.56, 0))
        assertFalse(t.add("A", "Tile", 53.7, 17.56, 60_000))
        assertTrue(t.add("B", "Tile", 53.7, 17.56, 60_000))
        assertTrue(t.add("A", "Tile", 53.71, 17.57, Trail.EVERY_MS))
        assertEquals(3, t.points.size)
        t.prune(Trail.KEEP_MS + 61_000)
        assertEquals(1, t.points.size)
    }

    @Test fun encodeDecode() {
        val t = Trail()
        t.add("A|x", "Tile\n", 53.70123, 17.56789, 1000)
        val d = Trail.decode(t.encode() + "\nśmieci")
        assertEquals(1, d.points.size)
        assertEquals("Ax", d.points[0].id)
        assertEquals(53.70123, d.points[0].lat, 1e-9)
    }

    @Test fun jsonSafe() {
        val t = Trail()
        t.add("A", "<script>\"", 1.0, 2.0, 5)
        val j = t.toJson(setOf("A"))
        assertFalse(j.contains("<script>"))
        assertTrue(j.contains("\"alert\":true"))
        assertTrue(j.contains("[2.00000,1.00000,5]"))
    }
}
