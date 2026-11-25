package com.hasan.airspeaker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Modern standard camera framing overlay with semi-transparent surround
 * and 4 smooth corner brackets.
 */
class QrScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val scrimPaint = Paint().apply {
        color = Color.parseColor("#99000000") // 60% translucent dark scrim
        style = Paint.Style.FILL
    }

    private val cornerPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 10f // ~3.5dp
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val frameRect = RectF()
    private val cornerLength = 70f // ~24dp
    private val cornerRadius = 18f // ~6dp

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val boxSize = (minOf(w, h) * 0.68f).coerceIn(240f, 750f)
        val left = (w - boxSize) / 2f
        val top = (h - boxSize) / 2f * 0.85f // slightly above dead center
        frameRect.set(left, top, left + boxSize, top + boxSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Draw 4 dim scrim rectangles outside frameRect
        canvas.drawRect(0f, 0f, w, frameRect.top, scrimPaint)
        canvas.drawRect(0f, frameRect.bottom, w, h, scrimPaint)
        canvas.drawRect(0f, frameRect.top, frameRect.left, frameRect.bottom, scrimPaint)
        canvas.drawRect(frameRect.right, frameRect.top, w, frameRect.bottom, scrimPaint)

        // Draw 4 smooth white corner brackets
        val l = frameRect.left
        val t = frameRect.top
        val r = frameRect.right
        val b = frameRect.bottom

        // Top-left corner
        val pathTL = Path().apply {
            moveTo(l, t + cornerLength)
            lineTo(l, t + cornerRadius)
            quadTo(l, t, l + cornerRadius, t)
            lineTo(l + cornerLength, t)
        }
        canvas.drawPath(pathTL, cornerPaint)

        // Top-right corner
        val pathTR = Path().apply {
            moveTo(r - cornerLength, t)
            lineTo(r - cornerRadius, t)
            quadTo(r, t, r, t + cornerRadius)
            lineTo(r, t + cornerLength)
        }
        canvas.drawPath(pathTR, cornerPaint)

        // Bottom-left corner
        val pathBL = Path().apply {
            moveTo(l, b - cornerLength)
            lineTo(l, b - cornerRadius)
            quadTo(l, b, l + cornerRadius, b)
            lineTo(l + cornerLength, b)
        }
        canvas.drawPath(pathBL, cornerPaint)

        // Bottom-right corner
        val pathBR = Path().apply {
            moveTo(r - cornerLength, b)
            lineTo(r - cornerRadius, b)
            quadTo(r, b, r, b - cornerRadius)
            lineTo(r, b - cornerLength)
        }
        canvas.drawPath(pathBR, cornerPaint)
    }
}
