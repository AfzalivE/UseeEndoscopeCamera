package com.afzal.endoscope

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A one-off message for the snackbar. [id] makes repeated texts show again. */
data class UiMessage(val id: Long, val text: String)

/** Media the user saved last, for the gallery button. */
data class SavedMedia(val uri: Uri, val mimeType: String)

/**
 * Owns the camera session: USB connection, frame processing, photos, video and the Wi-Fi stream.
 * Public state is Compose state and is only written on the main thread.
 */
class EndoscopeController(private val context: Context) : SupercameraConnection.Listener {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var connection: SupercameraConnection? = null
    private var processor: FrameProcessor? = null
    private var streamServer: StreamServer? = null
    private var recorder: VideoRecorder? = null
    private var permissionRequested = false
    private var messageId = 0L

    // region Compose state

    var connected by mutableStateOf(false)
        private set

    /** Shown in the empty state when there is no live image. */
    var emptyMessage by mutableStateOf(context.getString(R.string.no_camera))
        private set

    var hasImage by mutableStateOf(false)
        private set

    var resolution by mutableStateOf("")
        private set

    var fps by mutableStateOf(0f)
        private set

    var lens by mutableIntStateOf(1)
        private set

    var lensCount by mutableIntStateOf(0)
        private set

    var transform by mutableStateOf(ViewTransform())
        private set

    var recordingSeconds by mutableStateOf<Long?>(null)
        private set

    var streamAddress by mutableStateOf<String?>(null)
        private set

    var streamClient by mutableStateOf<String?>(null)
        private set

    var lastMedia by mutableStateOf<SavedMedia?>(null)
        private set

    var message by mutableStateOf<UiMessage?>(null)
        private set

    /** Increments on every photo, to trigger the shutter animation. */
    var shutterCount by mutableIntStateOf(0)
        private set

    // endregion

    // Written on the USB thread.
    @Volatile
    private var selectedLens = -1

    @Volatile
    private var seenLensMask = 0

    // Hardware button state, only used on the USB thread.
    private var buttonDownAt = 0L
    private var longPressFired = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            mainHandler.postDelayed(this, 500)
        }
    }

    var preview: PreviewView? = null
        set(value) {
            field = value
            processor?.preview = value
        }

    fun start() {
        processor = FrameProcessor().also {
            it.preview = preview
            it.transform = transform
            it.start()
        }
        connect()
        mainHandler.post(ticker)
    }

    fun stop() {
        mainHandler.removeCallbacks(ticker)
        stopRecording()
        stopStream()
        disconnect()
        processor?.shutdown()
        processor = null
        refresh()
    }

    fun release() {
        io.shutdown()
    }

    // region USB

    fun onDeviceAttached() {
        permissionRequested = false
        connect()
    }

    fun onDeviceDetached(deviceName: String?) {
        if (deviceName != null && deviceName == connection?.device?.deviceName) {
            disconnect()
            emptyMessage = context.getString(R.string.camera_disconnected)
        }
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) connect() else emptyMessage = context.getString(R.string.permission_denied)
    }

    fun connect() {
        if (connection != null || processor == null) return
        val devices = SupercameraConnection.findDevices(usbManager)
        if (devices.isEmpty()) {
            emptyMessage = context.getString(R.string.no_camera)
            return
        }
        val device = devices[0]
        if (!usbManager.hasPermission(device)) {
            emptyMessage = context.getString(R.string.waiting_permission)
            if (!permissionRequested) {
                permissionRequested = true
                val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
            }
            return
        }
        val conn = SupercameraConnection(usbManager, device)
        try {
            conn.start(this)
        } catch (e: IOException) {
            emptyMessage = context.getString(R.string.connect_failed, e.message)
            return
        }
        connection = conn
        selectedLens = -1
        seenLensMask = 0
        connected = true
        emptyMessage = context.getString(R.string.status_starting)
    }

    private fun disconnect() {
        stopRecording()
        connection?.stop()
        connection = null
        connected = false
        hasImage = false
        preview?.clear()
    }

    override fun onFrame(frame: UppFrame) {
        handleHardwareButton(frame.buttonPressed)
        seenLensMask = seenLensMask or (1 shl frame.camNum)
        if (selectedLens < 0) selectedLens = frame.camNum
        streamServer?.offer(frame.camNum, frame.timestampUs, frame.jpeg)
        if (frame.camNum == selectedLens) processor?.submit(frame)
    }

    override fun onStopped(error: String?) {
        mainHandler.post {
            if (error != null && connection != null) {
                disconnect()
                emptyMessage = context.getString(R.string.connect_failed, error)
            }
        }
    }

    /** Short press takes a photo, long press switches the lens (same as the original PoC). */
    private fun handleHardwareButton(pressed: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (pressed) {
            if (buttonDownAt == 0L) {
                buttonDownAt = now
                longPressFired = false
            } else if (!longPressFired && now - buttonDownAt >= LONG_PRESS_MS) {
                longPressFired = true
                mainHandler.post { switchLens() }
            }
        } else if (buttonDownAt != 0L) {
            if (!longPressFired) mainHandler.post { takePhoto() }
            buttonDownAt = 0L
        }
    }

    // endregion

    // region Actions

    fun updateTransform(value: ViewTransform) {
        transform = value
        processor?.transform = value
    }

    fun takePhoto() {
        val p = processor
        val bitmap = p?.lastBitmap
        val jpeg = p?.lastJpeg
        if (bitmap == null || jpeg == null) {
            show(context.getString(R.string.no_image))
            return
        }
        shutterCount++
        val t = transform
        io.execute {
            try {
                val bytes = if (t.isIdentity) jpeg else renderJpeg(bitmap, t)
                val uri = MediaStoreSaver.savePhoto(context, bytes)
                mainHandler.post {
                    lastMedia = SavedMedia(uri, "image/jpeg")
                    show(context.getString(R.string.photo_saved))
                }
            } catch (e: IOException) {
                mainHandler.post { show(context.getString(R.string.save_failed, e.message)) }
            }
        }
    }

    private fun renderJpeg(bitmap: Bitmap, t: ViewTransform): ByteArray {
        val w = t.outputWidth(bitmap.width, bitmap.height)
        val h = t.outputHeight(bitmap.width, bitmap.height)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bitmap, t.matrix(bitmap.width, bitmap.height, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val stream = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        out.recycle()
        return stream.toByteArray()
    }

    fun toggleRecording() {
        if (recorder != null) {
            stopRecording()
            return
        }
        val p = processor ?: return
        val bitmap = p.lastBitmap
        if (bitmap == null || connection == null) {
            show(context.getString(R.string.no_image))
            return
        }
        val w = transform.outputWidth(bitmap.width, bitmap.height)
        val h = transform.outputHeight(bitmap.width, bitmap.height)
        try {
            val r = VideoRecorder.start(context, w, h)
            recorder = r
            p.recorder = r
        } catch (e: IOException) {
            show(context.getString(R.string.record_failed, e.message))
        }
        refresh()
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        processor?.recorder = null
        refresh()
        io.execute {
            val uri = r.stop()
            mainHandler.post {
                if (uri != null) {
                    lastMedia = SavedMedia(uri, "video/mp4")
                    show(context.getString(R.string.video_saved))
                } else {
                    show(context.getString(R.string.record_failed, "no frames"))
                }
            }
        }
    }

    fun switchLens() {
        if (seenLensMask != 0b11) {
            show(context.getString(R.string.single_lens))
            return
        }
        selectedLens = if (selectedLens == 0) 1 else 0
        refresh()
        show(context.getString(R.string.lens_selected, selectedLens + 1))
    }

    fun toggleStream() {
        if (streamServer != null) {
            stopStream()
        } else {
            val server = StreamServer()
            try {
                server.start()
                streamServer = server
            } catch (e: IOException) {
                show(context.getString(R.string.stream_failed, e.message))
            }
        }
        refresh()
    }

    private fun stopStream() {
        streamServer?.stop()
        streamServer = null
    }

    fun show(text: String) {
        message = UiMessage(++messageId, text)
    }

    // endregion

    /** Copies values from worker threads into Compose state. Runs on the main thread. */
    private fun refresh() {
        val bitmap = processor?.lastBitmap
        hasImage = connected && bitmap != null
        if (bitmap != null) resolution = "${bitmap.width}×${bitmap.height}"
        fps = processor?.fps ?: 0f
        lens = if (selectedLens < 0) 1 else selectedLens + 1
        lensCount = Integer.bitCount(seenLensMask)
        recordingSeconds = recorder?.let { (System.currentTimeMillis() - it.startTimeMs) / 1000 }
        val server = streamServer
        streamAddress = server?.let { "${localIpAddress() ?: "?"}:${it.port}" }
        streamClient = server?.clientAddress
    }

    private fun localIpAddress(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        for (link in props.linkAddresses) {
            val address = link.address
            if (address is Inet4Address && !address.isLoopbackAddress) return address.hostAddress
        }
        return null
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.afzal.endoscope.USB_PERMISSION"
        private const val LONG_PRESS_MS = 1000L
    }
}
