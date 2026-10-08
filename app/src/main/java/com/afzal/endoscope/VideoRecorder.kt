package com.afzal.endoscope

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import java.io.IOException

/**
 * Records frames to an H.264 MP4 file. The camera sends JPEG images, so each frame is drawn
 * onto the encoder input surface. Timestamps come from the time each frame is drawn.
 */
class VideoRecorder private constructor(
    private val context: Context,
    val uri: Uri,
    private val pfd: ParcelFileDescriptor,
    private val width: Int,
    private val height: Int,
) {
    private val lock = Any()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val encoder: MediaCodec = MediaCodec.createEncoderByType(MIME)
    private val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val inputSurface: Surface
    private var drainThread: Thread? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var stopped = false

    @Volatile
    var framesWritten = 0
        private set

    val startTimeMs = System.currentTimeMillis()

    init {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
        drainThread = Thread({ drainLoop() }, "video-drain").apply { start() }
    }

    /** Draws one frame. Safe to call from the frame processing thread. */
    fun drawFrame(bitmap: Bitmap, transform: ViewTransform) {
        synchronized(lock) {
            if (stopped) return
            val canvas = try {
                inputSurface.lockHardwareCanvas()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot lock encoder surface", e)
                return
            }
            try {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(bitmap, transform.matrix(bitmap.width, bitmap.height, width, height), paint)
            } finally {
                inputSurface.unlockCanvasAndPost(canvas)
            }
            framesWritten++
        }
    }

    /** Finishes the file. Returns the video URI, or null when nothing was recorded. */
    fun stop(): Uri? {
        synchronized(lock) {
            if (stopped) return null
            stopped = true
        }
        try {
            encoder.signalEndOfInputStream()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "signalEndOfInputStream failed", e)
        }
        drainThread?.join(5000)
        var ok = muxerStarted
        try {
            encoder.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "encoder.stop failed", e)
        }
        encoder.release()
        inputSurface.release()
        try {
            if (muxerStarted) muxer.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "muxer.stop failed", e)
            ok = false
        }
        muxer.release()
        pfd.close()
        return if (ok) {
            MediaStoreSaver.publishVideo(context, uri)
            uri
        } else {
            context.contentResolver.delete(uri, null, null)
            null
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = try {
                encoder.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                return
            }
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val data = encoder.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (data != null && info.size > 0 && !isConfig && muxerStarted) {
                        data.position(info.offset)
                        data.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, data, info)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        private const val TAG = "VideoRecorder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val BIT_RATE = 4_000_000
        private const val FRAME_RATE = 30

        @Throws(IOException::class)
        fun start(context: Context, width: Int, height: Int): VideoRecorder {
            // Encoders want even sizes.
            val w = width and 1.inv()
            val h = height and 1.inv()
            val uri = MediaStoreSaver.createVideo(context)
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            if (pfd == null) {
                context.contentResolver.delete(uri, null, null)
                throw IOException("Cannot open video file")
            }
            try {
                return VideoRecorder(context, uri, pfd, w, h)
            } catch (e: Exception) {
                pfd.close()
                context.contentResolver.delete(uri, null, null)
                throw IOException("Cannot start video encoder: ${e.message}", e)
            }
        }
    }
}
