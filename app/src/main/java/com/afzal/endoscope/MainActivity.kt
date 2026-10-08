package com.afzal.endoscope

import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity(), SupercameraConnection.Listener {

    private lateinit var usbManager: UsbManager
    private lateinit var preview: PreviewView
    private lateinit var statusText: TextView
    private lateinit var recordingText: TextView
    private lateinit var streamText: TextView
    private lateinit var emptyText: TextView
    private lateinit var flashView: View
    private lateinit var zoomBar: SeekBar
    private lateinit var mirrorButton: Button
    private lateinit var flipButton: Button
    private lateinit var lensButton: Button
    private lateinit var streamButton: Button
    private lateinit var recordButton: Button
    private lateinit var lastButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private var connection: SupercameraConnection? = null
    private var processor: FrameProcessor? = null
    private var streamServer: StreamServer? = null
    private var transform = ViewTransform()
    private var lastMediaUri: Uri? = null
    private var lastMediaMime: String? = null
    private var permissionRequested = false

    // Written on the USB thread, read on the main thread.
    @Volatile
    private var selectedLens = -1

    @Volatile
    private var seenLensMask = 0

    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    // Hardware button state, only used on the USB thread.
    private var buttonDownAt = 0L
    private var longPressFired = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = usbDeviceFrom(intent)
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        connect()
                    } else {
                        showEmpty(getString(R.string.permission_denied))
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    permissionRequested = false
                    connect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device != null && device.deviceName == connection?.device?.deviceName) {
                        disconnect()
                        showEmpty(getString(R.string.camera_disconnected))
                    }
                }
            }
        }
    }

    private val statusUpdater = object : Runnable {
        override fun run() {
            updateStatus()
            mainHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        preview = findViewById(R.id.preview)
        statusText = findViewById(R.id.status_text)
        recordingText = findViewById(R.id.recording_text)
        streamText = findViewById(R.id.stream_text)
        emptyText = findViewById(R.id.empty_text)
        flashView = findViewById(R.id.flash)
        zoomBar = findViewById(R.id.zoom_bar)
        mirrorButton = findViewById(R.id.mirror_button)
        flipButton = findViewById(R.id.flip_button)
        lensButton = findViewById(R.id.lens_button)
        streamButton = findViewById(R.id.stream_button)
        recordButton = findViewById(R.id.record_button)
        lastButton = findViewById(R.id.last_button)

        findViewById<View>(R.id.photo_button).setOnClickListener { takePhoto() }
        recordButton.setOnClickListener { toggleRecording() }
        findViewById<View>(R.id.rotate_button).setOnClickListener { setTransform(transform.rotated()) }
        mirrorButton.setOnClickListener { setTransform(transform.copy(mirror = !transform.mirror)) }
        flipButton.setOnClickListener { setTransform(transform.copy(flip = !transform.flip)) }
        findViewById<View>(R.id.reset_button).setOnClickListener { setTransform(ViewTransform()) }
        lensButton.setOnClickListener { switchLens() }
        streamButton.setOnClickListener { toggleStream() }
        lastButton.setOnClickListener { openLastMedia() }

        zoomBar.max = ((ViewTransform.MAX_ZOOM - 1f) * 100).toInt()
        zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) setTransform(transform.withZoom(1f + progress / 100f))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setTransform(transform.withZoom(transform.zoom * detector.scaleFactor))
                return true
            }
        })
        preview.setOnTouchListener { _, event: MotionEvent ->
            scaleDetector.onTouchEvent(event)
            true
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        updateToggleButtons()
    }

    override fun onStart() {
        super.onStart()
        processor = FrameProcessor(preview).also {
            it.transform = transform
            it.start()
        }
        connect()
        mainHandler.post(statusUpdater)
    }

    override fun onStop() {
        mainHandler.removeCallbacks(statusUpdater)
        stopRecording()
        stopStream()
        disconnect()
        processor?.shutdown()
        processor = null
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        io.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            permissionRequested = false
            connect()
        }
    }

    // region USB connection

    private fun connect() {
        if (connection != null || processor == null) return
        val devices = SupercameraConnection.findDevices(usbManager)
        if (devices.isEmpty()) {
            showEmpty(getString(R.string.no_camera))
            return
        }
        val device = devices[0]
        if (!usbManager.hasPermission(device)) {
            showEmpty(getString(R.string.waiting_permission))
            if (!permissionRequested) {
                permissionRequested = true
                val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
            }
            return
        }
        val conn = SupercameraConnection(usbManager, device)
        try {
            conn.start(this)
        } catch (e: IOException) {
            showEmpty(getString(R.string.connect_failed, e.message))
            return
        }
        connection = conn
        selectedLens = -1
        seenLensMask = 0
        emptyText.visibility = View.GONE
    }

    private fun disconnect() {
        stopRecording()
        connection?.stop()
        connection = null
        frameWidth = 0
        frameHeight = 0
        preview.clear()
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
                showEmpty(getString(R.string.connect_failed, error))
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

    private fun setTransform(value: ViewTransform) {
        transform = value
        processor?.transform = value
        val progress = ((value.zoom - 1f) * 100).toInt()
        if (zoomBar.progress != progress) zoomBar.progress = progress
        updateToggleButtons()
    }

    private fun takePhoto() {
        val p = processor
        val bitmap = p?.lastBitmap
        val jpeg = p?.lastJpeg
        if (bitmap == null || jpeg == null) {
            toast(getString(R.string.no_image))
            return
        }
        flash()
        val t = transform
        io.execute {
            try {
                val bytes = if (t.isIdentity) jpeg else renderJpeg(bitmap, t)
                val uri = MediaStoreSaver.savePhoto(this, bytes)
                mainHandler.post {
                    setLastMedia(uri, "image/jpeg")
                    toast(getString(R.string.photo_saved))
                }
            } catch (e: IOException) {
                mainHandler.post { toast(getString(R.string.save_failed, e.message)) }
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

    private fun toggleRecording() {
        if (processor?.recorder != null) {
            stopRecording()
            return
        }
        val p = processor ?: return
        val bitmap = p.lastBitmap
        if (bitmap == null || connection == null) {
            toast(getString(R.string.no_image))
            return
        }
        val w = transform.outputWidth(bitmap.width, bitmap.height)
        val h = transform.outputHeight(bitmap.width, bitmap.height)
        try {
            p.recorder = VideoRecorder.start(this, w, h)
        } catch (e: IOException) {
            toast(getString(R.string.record_failed, e.message))
        }
        updateToggleButtons()
        updateStatus()
    }

    private fun stopRecording() {
        val p = processor ?: return
        val recorder = p.recorder ?: return
        p.recorder = null
        updateToggleButtons()
        updateStatus()
        io.execute {
            val uri = recorder.stop()
            mainHandler.post {
                if (uri != null) {
                    setLastMedia(uri, "video/mp4")
                    toast(getString(R.string.video_saved))
                } else {
                    toast(getString(R.string.record_failed, "no frames"))
                }
            }
        }
    }

    private fun switchLens() {
        val mask = seenLensMask
        if (mask != 0b11) {
            toast(getString(R.string.single_lens))
            return
        }
        selectedLens = if (selectedLens == 0) 1 else 0
        toast(getString(R.string.lens_selected, selectedLens + 1))
    }

    private fun toggleStream() {
        if (streamServer != null) {
            stopStream()
        } else {
            val server = StreamServer()
            try {
                server.start()
                streamServer = server
            } catch (e: IOException) {
                toast(getString(R.string.stream_failed, e.message))
            }
        }
        updateToggleButtons()
        updateStatus()
    }

    private fun stopStream() {
        streamServer?.stop()
        streamServer = null
    }

    private fun setLastMedia(uri: Uri, mime: String) {
        lastMediaUri = uri
        lastMediaMime = mime
        lastButton.isEnabled = true
    }

    private fun openLastMedia() {
        val uri = lastMediaUri ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, lastMediaMime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_viewer))
        }
    }

    // endregion

    // region UI

    private fun updateToggleButtons() {
        mirrorButton.isSelected = transform.mirror
        flipButton.isSelected = transform.flip
        streamButton.isSelected = streamServer != null
        val recording = processor?.recorder != null
        recordButton.isSelected = recording
        recordButton.text = getString(if (recording) R.string.stop else R.string.record)
        lastButton.isEnabled = lastMediaUri != null
        lensButton.text = getString(R.string.lens_button, if (selectedLens < 0) 1 else selectedLens + 1)
    }

    private fun updateStatus() {
        val p = processor
        val bitmap = p?.lastBitmap
        if (connection == null) {
            statusText.text = getString(R.string.status_disconnected)
        } else if (bitmap == null) {
            statusText.text = getString(R.string.status_starting)
        } else {
            frameWidth = bitmap.width
            frameHeight = bitmap.height
            val lenses = Integer.bitCount(seenLensMask)
            statusText.text = getString(
                R.string.status_streaming,
                frameWidth,
                frameHeight,
                String.format(Locale.US, "%.1f", p.fps),
                selectedLens + 1,
                lenses,
            )
        }
        lensButton.text = getString(R.string.lens_button, if (selectedLens < 0) 1 else selectedLens + 1)

        val recorder = p?.recorder
        if (recorder != null) {
            val seconds = (System.currentTimeMillis() - recorder.startTimeMs) / 1000
            recordingText.text = getString(R.string.recording, seconds / 60, seconds % 60)
            recordingText.visibility = View.VISIBLE
        } else {
            recordingText.visibility = View.GONE
        }

        val server = streamServer
        if (server != null) {
            val ip = localIpAddress() ?: "?"
            val client = server.clientAddress
            streamText.text = if (client != null) {
                getString(R.string.stream_client, ip, server.port, client)
            } else {
                getString(R.string.stream_waiting, ip, server.port)
            }
            streamText.visibility = View.VISIBLE
        } else {
            streamText.visibility = View.GONE
        }
    }

    private fun showEmpty(message: String) {
        emptyText.text = message
        emptyText.visibility = View.VISIBLE
    }

    private fun flash() {
        flashView.alpha = 0.8f
        flashView.visibility = View.VISIBLE
        flashView.animate().alpha(0f).setDuration(250).withEndAction { flashView.visibility = View.GONE }.start()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun localIpAddress(): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        for (link in props.linkAddresses) {
            val address = link.address
            if (address is Inet4Address && !address.isLoopbackAddress) return address.hostAddress
        }
        return null
    }

    // endregion

    @Suppress("DEPRECATION")
    private fun usbDeviceFrom(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.afzal.endoscope.USB_PERMISSION"
        private const val LONG_PRESS_MS = 1000L
    }
}
