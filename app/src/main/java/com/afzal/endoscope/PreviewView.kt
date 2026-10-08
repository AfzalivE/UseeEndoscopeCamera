package com.afzal.endoscope

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/** Draws decoded camera frames. [draw] may be called from any thread. */
class PreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val lock = Any()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var ready = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    init {
        holder.addCallback(this)
    }

    fun draw(bitmap: Bitmap, transform: ViewTransform) {
        synchronized(lock) {
            if (!ready || surfaceWidth == 0 || surfaceHeight == 0) return
            val canvas = holder.lockCanvas() ?: return
            try {
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(
                    bitmap,
                    transform.matrix(bitmap.width, bitmap.height, surfaceWidth, surfaceHeight),
                    paint,
                )
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            if (!ready) return
            val canvas = holder.lockCanvas() ?: return
            canvas.drawColor(Color.BLACK)
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        synchronized(lock) { ready = true }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) {
            surfaceWidth = width
            surfaceHeight = height
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        synchronized(lock) { ready = false }
    }
}
