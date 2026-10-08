package com.afzal.endoscope

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamHeaderTest {
    @Test
    fun magicSpellsGsvc() {
        val bytes = java.nio.ByteBuffer.allocate(4).putInt(StreamServer.MAGIC).array()
        assertEquals("GSVC", String(bytes, Charsets.US_ASCII))
        assertEquals(28, StreamServer.HEADER_SIZE)
    }
}
