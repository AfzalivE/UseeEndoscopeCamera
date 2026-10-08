package com.afzal.endoscope

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.afzal.endoscope.ui.EndoscopeScreen
import com.afzal.endoscope.ui.EndoscopeTheme

class MainActivity : ComponentActivity() {

    private lateinit var controller: EndoscopeController

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                EndoscopeController.ACTION_USB_PERMISSION ->
                    controller.onPermissionResult(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> controller.onDeviceAttached()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> controller.onDeviceDetached(usbDeviceFrom(intent)?.deviceName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = EndoscopeController(applicationContext)

        val filter = IntentFilter().apply {
            addAction(EndoscopeController.ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        setContent {
            EndoscopeTheme {
                EndoscopeScreen(controller = controller, onOpenMedia = ::openLastMedia)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.start()
    }

    override fun onStop() {
        controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        controller.release()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) controller.onDeviceAttached()
    }

    private fun openLastMedia() {
        val media = controller.lastMedia ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(media.uri, media.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            controller.show(getString(R.string.no_viewer))
        }
    }

    @Suppress("DEPRECATION")
    private fun usbDeviceFrom(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
