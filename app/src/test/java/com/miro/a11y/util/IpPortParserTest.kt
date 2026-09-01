package com.miro.a11y.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IpPortParserTest {

    @Test fun `parses well-formed IP:port`() {
        val r = IpPortParser.parse("10.42.1.63:5555")
        assertNotNull(r)
        assertEquals("10.42.1.63", r!!.ip)
        assertEquals(5555, r.port)
    }

    @Test fun `parses with surrounding text`() {
        val r = IpPortParser.parse("Wireless debug: 10.42.1.63:43661")
        assertNotNull(r)
        assertEquals("10.42.1.63", r!!.ip)
        assertEquals(43661, r.port)
    }

    @Test fun `returns null for empty string`() {
        assertNull(IpPortParser.parse(""))
    }

    @Test fun `returns null for null input`() {
        assertNull(IpPortParser.parse(null))
    }

    @Test fun `returns null for malformed input`() {
        assertNull(IpPortParser.parse("not an ip:port"))
    }
}
