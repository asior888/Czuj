package com.asior.czuj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun states() {
        val i = Updates.parse("""{"najnowsza":20,"minimalna":15}""")
        assertEquals(Updates.State.OK, Updates.state(20, i))
        assertEquals(Updates.State.BANNER, Updates.state(17, i))
        assertEquals(Updates.State.BLOCK, Updates.state(14, i))
        assertEquals(Updates.State.OK, Updates.state(5, null))
    }

    @Test fun linksOnlyTrustedHosts() {
        val i = Updates.parse("""{"najnowsza":3,"pobierz":"https://evil.example/czuj.apk",
            "wsparcie":"http://tipply.pl/asior","api":"https://czuj.asior.workers.dev/"}""")!!
        assertEquals(Updates.RELEASES, i.download)
        assertEquals(Updates.SUPPORT, i.support)
        assertEquals("https://czuj.asior.workers.dev", i.api)
        assertTrue(Updates.safe("https://tipply.pl/asior", listOf("tipply.pl")))
        assertFalse(Updates.safe("https://tipply.pl.evil.com/x", listOf("tipply.pl")))
        assertFalse(Updates.safe("https://user@github.com/x", listOf("github.com")))
        assertEquals("", Updates.parse("""{"api":"https://evil.com"}""")!!.api)
    }

    @Test fun broken() {
        assertNull(Updates.parse("<html>"))
    }
}
