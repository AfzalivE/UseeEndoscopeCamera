package com.afzal.endoscope

import android.graphics.Matrix

/** How the user wants the image shown and saved: rotation, mirroring and digital zoom. */
data class ViewTransform(
    /** Clockwise rotation in degrees: 0, 90, 180 or 270. */
    val rotation: Int = 0,
    /** Flip left-right, after rotation. */
    val mirror: Boolean = false,
    /** Flip top-bottom, after rotation. */
    val flip: Boolean = false,
    /** Digital zoom around the image center, 1 to [MAX_ZOOM]. */
    val zoom: Float = 1f,
) {
    val isIdentity: Boolean
        get() = rotation == 0 && !mirror && !flip && zoom == 1f

    fun outputWidth(srcWidth: Int, srcHeight: Int): Int = if (rotation % 180 == 0) srcWidth else srcHeight

    fun outputHeight(srcWidth: Int, srcHeight: Int): Int = if (rotation % 180 == 0) srcHeight else srcWidth

    /** Maps a [srcWidth]x[srcHeight] bitmap into a [dstWidth]x[dstHeight] area, centered and letterboxed. */
    fun matrix(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): Matrix {
        val rotatedWidth = outputWidth(srcWidth, srcHeight).toFloat()
        val rotatedHeight = outputHeight(srcWidth, srcHeight).toFloat()
        val fit = Math.min(dstWidth / rotatedWidth, dstHeight / rotatedHeight) * zoom
        val m = Matrix()
        m.postTranslate(-srcWidth / 2f, -srcHeight / 2f)
        m.postRotate(rotation.toFloat())
        m.postScale(if (mirror) -fit else fit, if (flip) -fit else fit)
        m.postTranslate(dstWidth / 2f, dstHeight / 2f)
        return m
    }

    fun rotated(): ViewTransform = copy(rotation = (rotation + 90) % 360)

    fun withZoom(value: Float): ViewTransform = copy(zoom = Math.max(1f, Math.min(MAX_ZOOM, value)))

    companion object {
        const val MAX_ZOOM = 4f
    }
}
