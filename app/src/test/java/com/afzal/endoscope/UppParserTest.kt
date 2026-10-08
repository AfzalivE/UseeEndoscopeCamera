package com.afzal.endoscope

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UppParserTest {

    private fun packet(fid: Int, cam: Int, payload: ByteArray, button: Boolean = false, cid: Int = 7): ByteArray {
        val length = 7 + payload.size
        val header = byteArrayOf(
            0xAA.toByte(), 0xBB.toByte(), cid.toByte(), (length and 0xFF).toByte(), (length shr 8).toByte(),
            fid.toByte(), cam.toByte(), (if (button) 0x02 else 0x00).toByte(), 0, 0, 0, 0,
        )
        return header + payload
    }

    private fun feed(parser: UppParser, data: ByteArray) = parser.handle(data, data.size)

    @Test
    fun reassemblesChunksIntoOneFrame() {
        val frames = ArrayList<UppFrame>()
        val parser = UppParser { frames.add(it) }
        feed(parser, packet(1, 0, byteArrayOf(1, 2, 3)))
        feed(parser, packet(1, 0, byteArrayOf(4, 5)))
        assertTrue(frames.isEmpty())
        feed(parser, packet(2, 0, byteArrayOf(9)))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), frames[0].jpeg)
        assertEquals(0, frames[0].camNum)
        assertFalse(frames[0].buttonPressed)
        parser.flush()
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(9), frames[1].jpeg)
        assertEquals(1, frames[1].frameId)
    }

    @Test
    fun reportsButtonAndLens() {
        val frames = ArrayList<UppFrame>()
        val parser = UppParser { frames.add(it) }
        feed(parser, packet(5, 1, byteArrayOf(1), button = true, cid = 11))
        parser.flush()
        assertEquals(1, frames.size)
        assertTrue(frames[0].buttonPressed)
        assertEquals(1, frames[0].camNum)
    }

    @Test
    fun ignoresInvalidPackets() {
        val frames = ArrayList<UppFrame>()
        val parser = UppParser { frames.add(it) }
        val bad = packet(1, 0, byteArrayOf(1)).also { it[0] = 0 }
        feed(parser, bad) // wrong magic
        feed(parser, packet(1, 0, byteArrayOf(1), cid = 3)) // wrong cid
        feed(parser, packet(1, 2, byteArrayOf(1))) // camera number out of range
        val truncated = packet(1, 0, byteArrayOf(1, 2, 3))
        parser.handle(truncated, truncated.size - 1) // shorter than declared length
        parser.flush()
        assertTrue(frames.isEmpty())
    }

    @Test
    fun dropsChunksFromAnotherLensInsideAFrame() {
        val frames = ArrayList<UppFrame>()
        val parser = UppParser { frames.add(it) }
        feed(parser, packet(1, 0, byteArrayOf(1)))
        feed(parser, packet(1, 1, byteArrayOf(2)))
        parser.flush()
        assertArrayEquals(byteArrayOf(1), frames[0].jpeg)
    }
}
