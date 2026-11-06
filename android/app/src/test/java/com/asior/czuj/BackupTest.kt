package com.asior.czuj

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupTest {
    private val data = """{"czuj":1,"mine":{"AA":"Tile"}}""".toByteArray()

    @Test fun roundTrip() {
        val f = Backup.seal(data, "dobrehaslo1", 1000)
        assertArrayEquals(data, Backup.open(f, "dobrehaslo1", 1000))
    }

    @Test fun wrongPasswordOrDamaged() {
        val f = Backup.seal(data, "dobrehaslo1", 1000)
        assertNull(Backup.open(f, "zlehaslo12", 1000))
        val bad = f.copyOf(); bad[bad.size - 1] = (bad[bad.size - 1] + 1).toByte()
        assertNull(Backup.open(bad, "dobrehaslo1", 1000))
        assertNull(Backup.open("cos innego".toByteArray(), "dobrehaslo1", 1000))
    }

    @Test(expected = IllegalArgumentException::class) fun shortPassword() {
        Backup.seal(data, "krotkie", 1000)
    }
}
