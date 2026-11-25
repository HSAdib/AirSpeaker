package com.hasan.airspeaker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.util.ArrayDeque

class LatencyGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxPoints = 60
    private val bufferHistory = ArrayDeque<Float>(maxPoints)
    private var currentTargetMs = 30f
    private var currentBufferMs = 0f
    private var estTotalMs = 55f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#171B22"); style = Paint.Style.FILL }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#202632"); style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2C3340"); style = Paint.Style.STROKE; strokeWidth = 2.5f }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#252C38"); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9BA3B4")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7BDCA8")
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D3E3FD")
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val path = Path()
    private val fillPath = Path()
    private val clipPath = Path()
    private val outerRect = RectF()
    private val cornerRadiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, resources.displayMetrics)

    init {
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        for (i in 0 until maxPoints) bufferHistory.add(0f)
    }

    fun pushSample(bufferMs: Float, targetMs: Float, totalEstimatedMs: Float) {
        if (bufferHistory.size >= maxPoints) bufferHistory.pollFirst()
        bufferHistory.addLast(bufferMs.coerceIn(0f, 500f))
        currentBufferMs = bufferMs
        currentTargetMs = targetMs
        estTotalMs = totalEstimatedMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val headerH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics)
        val plotH = (h - headerH).coerceAtLeast(10f)

        outerRect.set(1.5f, 1.5f, w - 1.5f, h - 1.5f)
        clipPath.reset()
        clipPath.addRoundRect(outerRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)

        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val maxScaleMs = maxOf(160f, currentTargetMs + 40f)

        for (ms in listOf(40f, 80f, 120f)) {
            if (ms < maxScaleMs) {
                val y = h - (ms / maxScaleMs) * plotH
                canvas.drawLine(0f, y, w, y, gridPaint)
            }
        }

        val targetY = (h - (currentTargetMs.coerceIn(0f, maxScaleMs) / maxScaleMs) * plotH).coerceIn(headerH + 3f, h - 3f)
        canvas.drawLine(0f, targetY, w, targetY, targetPaint)

        path.reset()
        fillPath.reset()
        val stepX = w / (maxPoints - 1).toFloat()
        var idx = 0
        for (v in bufferHistory) {
            val x = idx * stepX
            val y = (h - (v.coerceIn(0f, maxScaleMs) / maxScaleMs) * plotH).coerceIn(headerH + 3f, h - 3f)
            if (idx == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            idx++
        }
        fillPath.lineTo(w, h)
        fillPath.close()

        val isWarn = currentBufferMs > currentTargetMs + 20f
        val strokeColor = if (isWarn) Color.parseColor("#FFB74D") else Color.parseColor("#7BDCA8")
        val topFill = if (isWarn) Color.parseColor("#44FFB74D") else Color.parseColor("#447BDCA8")
        linePaint.color = strokeColor
        fillPaint.shader = LinearGradient(0f, headerH, 0f, h, topFill, Color.TRANSPARENT, Shader.TileMode.CLAMP)

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        canvas.drawRect(0f, 0f, w, headerH, headerPaint)
        canvas.drawLine(0f, headerH, w, headerH, gridPaint)

        val label = String.format(java.util.Locale.US, "Buffer %.1f ms  •  Est. ~%.0f ms  •  Target %.0f ms", currentBufferMs, estTotalMs, currentTargetMs)
        val textY = headerH * 0.68f
        canvas.drawText(label, 20f, textY, textPaint)

        canvas.restore()
        canvas.drawRoundRect(outerRect, cornerRadiusPx, cornerRadiusPx, borderPaint)
    }
}
