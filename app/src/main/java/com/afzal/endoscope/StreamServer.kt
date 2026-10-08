package com.afzal.endoscope

import android.util.Log
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TCP server that sends frames in the "Supercamera Stream Protocol (v1)" format, so the
 * `scripts/stream_receiver.py` and `scripts/tcp_stream_to_ros.py` tools from
 * https://github.com/jmz3/EndoscopeCamera can show the phone's camera feed.
 *
 * Like the original sender, it serves one client at a time and keeps only the latest frame per source.
 */
class StreamServer(val port: Int = DEFAULT_PORT) {

    private class Pending(val sourceId: Int, val frameId: Int, val timestampUs: Long, val jpeg: ByteArray)

    private val lock = Any()
    private val latest = arrayOfNulls<Pending>(MAX_SOURCES)
    private val frameCounters = IntArray(MAX_SOURCES)

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var client: Socket? = null
    private var thread: Thread? = null

    @Volatile
    var clientAddress: String? = null
        private set

    @Throws(IOException::class)
    fun start() {
        val socket = ServerSocket(port)
        socket.reuseAddress = true
        serverSocket = socket
        running = true
        thread = Thread({ acceptLoop(socket) }, "stream-server").apply { start() }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        synchronized(lock) {
            try {
                client?.close()
            } catch (_: IOException) {
            }
            (lock as Object).notifyAll()
        }
        thread?.join(2000)
        thread = null
    }

    /** Queues a frame. Older unsent frames from the same source are dropped. */
    fun offer(sourceId: Int, timestampUs: Long, jpeg: ByteArray) {
        if (!running || sourceId !in 0 until MAX_SOURCES) return
        synchronized(lock) {
            latest[sourceId] = Pending(sourceId, frameCounters[sourceId]++, timestampUs, jpeg)
            (lock as Object).notifyAll()
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            socket.tcpNoDelay = true
            synchronized(lock) {
                client = socket
                latest.fill(null)
            }
            clientAddress = socket.inetAddress?.hostAddress
            try {
                sendLoop(socket)
            } catch (e: IOException) {
                Log.i(TAG, "Client disconnected: ${e.message}")
            } finally {
                clientAddress = null
                try {
                    socket.close()
                } catch (_: IOException) {
                }
                synchronized(lock) { client = null }
            }
        }
    }

    private fun sendLoop(socket: Socket) {
        val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        while (running) {
            val frames = ArrayList<Pending>(MAX_SOURCES)
            synchronized(lock) {
                while (running && latest.all { it == null }) (lock as Object).wait(500)
                for (i in latest.indices) {
                    latest[i]?.let { frames.add(it) }
                    latest[i] = null
                }
            }
            for (frame in frames) {
                header.clear()
                header.putInt(MAGIC)
                header.put(VERSION.toByte())
                header.put(CODEC_JPEG.toByte())
                header.putShort(0) // flags
                header.putShort(frame.sourceId.toShort())
                header.putShort(0) // reserved
                header.putInt(frame.frameId)
                header.putLong(frame.timestampUs)
                header.putInt(frame.jpeg.size)
                out.write(header.array(), 0, HEADER_SIZE)
                out.write(frame.jpeg)
            }
            out.flush()
        }
    }

    companion object {
        private const val TAG = "StreamServer"
        const val DEFAULT_PORT = 9000
        const val HEADER_SIZE = 28
        const val MAGIC = 0x47535643 // "GSVC"
        const val VERSION = 1
        const val CODEC_JPEG = 1
        private const val MAX_SOURCES = 2
    }
}
