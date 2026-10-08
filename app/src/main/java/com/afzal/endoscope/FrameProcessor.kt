package com.afzal.endoscope

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes the newest frame and sends it to the preview and the video recorder.
 * Runs on its own thread so slow decoding never blocks USB reads; frames that arrive
 * while a decode is in progress are dropped.
 */
class FrameProcessor(private val preview: PreviewView) : Thread("frame-processor") {

    private val queue = ArrayBlockingQueue<UppFrame>(1)

    @Volatile
    private var running = true

    @Volatile
    var transform = ViewTransform()

    @Volatile
    var recorder: VideoRecorder? = null

    /** Last decoded frame, used for photos. */
    @Volatile
    var lastBitmap: Bitmap? = null
        private set

    @Volatile
    var lastJpeg: ByteArray? = null
        private set

    @Volatile
    var fps = 0f
        private set

    private var fpsWindowStart = 0L
    private var fpsCount = 0

    fun submit(frame: UppFrame) {
        queue.clear()
        queue.offer(frame)
    }

    fun shutdown() {
        running = false
        interrupt()
        join(2000)
    }

    override fun run() {
        while (running) {
            val frame = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            } ?: continue
            val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size) ?: continue
            lastBitmap = bitmap
            lastJpeg = frame.jpeg
            val t = transform
            preview.draw(bitmap, t)
            recorder?.drawFrame(bitmap, t)
            countFps()
        }
    }

    private fun countFps() {
        val now = System.nanoTime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsCount++
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1_000_000_000L) {
            fps = fpsCount * 1e9f / elapsed
            fpsCount = 0
            fpsWindowStart = now
        }
    }
}
