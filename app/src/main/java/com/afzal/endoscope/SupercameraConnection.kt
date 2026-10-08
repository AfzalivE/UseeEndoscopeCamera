package com.afzal.endoscope

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.IOException

/**
 * Talks to a "Geek szitman supercamera" endoscope over the Android USB host API.
 *
 * Port of `UsbSupercamera` from https://github.com/jmz3/EndoscopeCamera (src/supercamera_core.cpp).
 * The camera is not a UVC device: it uses a vendor protocol on two bulk interfaces.
 */
class SupercameraConnection(
    private val usbManager: UsbManager,
    val device: UsbDevice,
) {
    interface Listener {
        /** Called on the USB read thread for every complete frame. */
        fun onFrame(frame: UppFrame)

        /** Called on the USB read thread when streaming ends. [error] is null on a normal stop. */
        fun onStopped(error: String?)
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var connection: UsbDeviceConnection? = null

    /** Opens the device, sends the start sequence and starts the read thread. */
    @Throws(IOException::class)
    fun start(listener: Listener) {
        val conn = usbManager.openDevice(device) ?: throw IOException("Cannot open USB device")
        try {
            val ifaceA = findInterface(INTERFACE_A_NUMBER, 0) ?: throw IOException("Interface A not found")
            val ifaceB = findInterface(INTERFACE_B_NUMBER, INTERFACE_B_ALTERNATE_SETTING)
                ?: throw IOException("Interface B alt 1 not found")

            if (!conn.claimInterface(ifaceA, true)) throw IOException("Claim interface A failed")
            if (!conn.claimInterface(ifaceB, true)) throw IOException("Claim interface B failed")
            if (!conn.setInterface(ifaceB)) throw IOException("Set interface B alt setting failed")

            val ep1In = findEndpoint(ifaceB, UsbConstants.USB_DIR_IN, 1) ?: throw IOException("EP1 IN not found")
            val ep1Out = findEndpoint(ifaceB, UsbConstants.USB_DIR_OUT, 1) ?: throw IOException("EP1 OUT not found")
            val ep2Out = findEndpoint(ifaceA, UsbConstants.USB_DIR_OUT, 2) ?: throw IOException("EP2 OUT not found")

            // libusb_clear_halt(handle, ENDPOINT_1): standard CLEAR_FEATURE(ENDPOINT_HALT) request.
            val cleared = conn.controlTransfer(0x02, 0x01, 0, ep1Out.address, null, 0, USB_TIMEOUT)
            if (cleared < 0) Log.w(TAG, "Clear halt on EP1 failed, continuing")

            write(conn, ep2Out, START_SEQUENCE_EP2, "start sequence EP2")
            write(conn, ep1Out, START_STREAM, "start stream command")

            connection = conn
            running = true
            thread = Thread({ readLoop(conn, ep1In, listener) }, "supercamera-usb").apply { start() }
        } catch (e: IOException) {
            conn.close()
            throw e
        }
    }

    fun stop() {
        running = false
        val t = thread
        if (t != null && t !== Thread.currentThread()) t.join(2000)
        thread = null
        connection?.close()
        connection = null
    }

    private fun readLoop(conn: UsbDeviceConnection, epIn: UsbEndpoint, listener: Listener) {
        val parser = UppParser { listener.onFrame(it) }
        val buf = ByteArray(READ_SIZE)
        var failures = 0
        var error: String? = null
        while (running) {
            val n = conn.bulkTransfer(epIn, buf, buf.size, USB_TIMEOUT)
            if (n > 0) {
                failures = 0
                parser.handle(buf, n)
            } else if (n < 0) {
                // Android reports timeouts and errors the same way. Give up after a few seconds of silence.
                failures++
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    error = "The camera stopped sending data"
                    break
                }
            }
        }
        parser.flush()
        running = false
        listener.onStopped(error)
    }

    private fun write(conn: UsbDeviceConnection, ep: UsbEndpoint, data: ByteArray, what: String) {
        val n = conn.bulkTransfer(ep, data, data.size, USB_TIMEOUT)
        if (n != data.size) throw IOException("$what failed")
    }

    private fun findInterface(id: Int, alternate: Int): UsbInterface? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id == id && iface.alternateSetting == alternate) return iface
        }
        return null
    }

    private fun findEndpoint(iface: UsbInterface, direction: Int, number: Int): UsbEndpoint? {
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == direction &&
                ep.endpointNumber == number
            ) {
                return ep
            }
        }
        return null
    }

    companion object {
        private const val TAG = "Supercamera"

        /** Vendor and product IDs from the source repository. Keep in sync with res/xml/device_filter.xml. */
        private val SUPPORTED_IDS = arrayOf(
            intArrayOf(0x2ce3, 0x3828),
            intArrayOf(0x0329, 0x2022),
        )

        private const val INTERFACE_A_NUMBER = 0
        private const val INTERFACE_B_NUMBER = 1
        private const val INTERFACE_B_ALTERNATE_SETTING = 1
        private const val USB_TIMEOUT = 1000
        private const val READ_SIZE = 0x400
        private const val MAX_CONSECUTIVE_FAILURES = 5

        private val START_SEQUENCE_EP2 = byteArrayOf(0xFF.toByte(), 0x55, 0xFF.toByte(), 0x55, 0xEE.toByte(), 0x10)
        private val START_STREAM = byteArrayOf(0xBB.toByte(), 0xAA.toByte(), 5, 0, 0)

        fun isSupported(device: UsbDevice): Boolean {
            for (ids in SUPPORTED_IDS) {
                if (device.vendorId == ids[0] && device.productId == ids[1]) return true
            }
            return false
        }

        fun findDevices(usbManager: UsbManager): List<UsbDevice> {
            val result = ArrayList<UsbDevice>()
            for (device in usbManager.deviceList.values) {
                if (isSupported(device)) result.add(device)
            }
            return result
        }
    }
}
