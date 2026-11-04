package com.asior.czuj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudTest {
    @Test fun cellIsCoarse() {
        assertEquals("53.70:17.56", Cloud.cell(53.7012, 17.5601))
        assertEquals("53.70:17.56", Cloud.cell(53.709, 17.569))
        assertEquals("0.00:0.00", Cloud.cell(-0.001, 0.001))
        assertEquals("-33.86:151.20", Cloud.cell(-33.8688, 151.2093))
    }

    @Test fun cellMatchesRulesFormat() {
        val re = Regex("^-?[0-9]{1,2}[.][0-9]{2}:-?[0-9]{1,3}[.][0-9]{2}$")
        for ((la, lo) in listOf(53.7 to 17.5, -89.99 to -179.99, 0.0 to 0.0, 12.345 to 123.456)) {
            assert(re.matches(Cloud.cell(la, lo))) { Cloud.cell(la, lo) }
        }
    }

    @Test fun typeMapping() {
        assertEquals("airtag", Cloud.typeOf("AirTag / Find My"))
        assertEquals("tile", Cloud.typeOf("Tile"))
        assertEquals("smarttag", Cloud.typeOf("SmartTag (Samsung)"))
        assertNull(Cloud.typeOf("coś innego"))
    }
}
