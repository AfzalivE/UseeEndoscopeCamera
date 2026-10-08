package com.afzal.endoscope

import java.io.ByteArrayOutputStream

/** One complete JPEG image received from the endoscope. */
class UppFrame(
    val jpeg: ByteArray,
    /** Lens index reported by the camera (0 or 1 on dual-lens models). */
    val camNum: Int,
    /** True when the hardware button bit was set in any chunk of this frame. */
    val buttonPressed: Boolean,
    val frameId: Int,
    val timestampUs: Long,
)

/**
 * Reassembles JPEG frames from the "com.useeplus.protocol" USB packets.
 *
 * Port of `UPPCameraParser` from https://github.com/jmz3/EndoscopeCamera (src/supercamera_core.cpp).
 *
 * USB packet layout (little-endian):
 * ```
 * u16 magic (0xBBAA) | u8 cid (7 or 11) | u16 length
 * u8 fid | u8 cam_num | u8 flags (bit0 has_g, bit1 button, bits2-7 other) | u32 g_sensor
 * JPEG chunk (length - 7 bytes)
 * ```
 * A frame ends when a packet with a different `fid` arrives.
 */
class UppParser(private val onFrame: (UppFrame) -> Unit) {

    private val buffer = ByteArrayOutputStream(128 * 1024)
    private var fid = 0
    private var camNum = 0
    private var hasG = 0
    private var other = 0
    private var buttonInFrame = false
    private var frameId = 0

    fun handle(data: ByteArray, size: Int) {
        if (size < USB_HEADER_LEN) return

        val magic = u16(data, 0)
        val cid = data[2].toInt() and 0xFF
        val length = u16(data, 3)

        if (magic != UPP_USB_MAGIC) return
        if (cid != UPP_CAMID_7 && cid != UPP_CAMID_11) return
        if (USB_HEADER_LEN + length > size) return
        if (size - USB_HEADER_LEN < CAM_HEADER_LEN) return
        if (length < CAM_HEADER_LEN) return

        val partFid = data[5].toInt() and 0xFF
        val partCam = data[6].toInt() and 0xFF
        val flags = data[7].toInt() and 0xFF
        val partHasG = flags and 0x01
        val partButton = (flags shr 1) and 0x01
        val partOther = (flags shr 2) and 0x3F

        if (buffer.size() > 0 && fid != partFid) {
            emitFrame()
        }

        if (buffer.size() == 0) {
            fid = partFid
            camNum = partCam
            hasG = partHasG
            other = partOther
            if (!(camNum < 2 && hasG == 0 && other == 0)) return
        } else {
            if (!(fid == partFid && camNum == partCam && hasG == partHasG && other == partOther)) return
        }

        if (partButton != 0) buttonInFrame = true

        val start = USB_HEADER_LEN + CAM_HEADER_LEN
        val end = USB_HEADER_LEN + length
        if (end > start) buffer.write(data, start, end - start)
    }

    /** Emits a partially received frame, if any. */
    fun flush() {
        emitFrame()
    }

    private fun emitFrame() {
        if (buffer.size() == 0) return
        val frame = UppFrame(
            jpeg = buffer.toByteArray(),
            camNum = camNum,
            buttonPressed = buttonInFrame,
            frameId = frameId++,
            timestampUs = System.currentTimeMillis() * 1000L,
        )
        buffer.reset()
        buttonInFrame = false
        onFrame(frame)
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    companion object {
        const val USB_HEADER_LEN = 5
        const val CAM_HEADER_LEN = 7
        const val UPP_USB_MAGIC = 0xBBAA
        const val UPP_CAMID_7 = 7
        const val UPP_CAMID_11 = 11
    }
}
