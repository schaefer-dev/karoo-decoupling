package com.karoo_decoupling.extension

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import java.util.Locale

/**
 * Draws the decoupling-trend field: a sparkline of whole-ride driftPct history with the
 * trailing-20-min delta + trend arrow baked on top. Mirrors sk0711-graph's GraphRenderer
 * (Canvas onto a reusable ARGB_8888 bitmap). No SDK types — only Android graphics.
 */
object DecouplingTrendRenderer {

    /** Delta magnitude (percentage points) below which the trend reads as flat. */
    private const val FLAT_THRESHOLD = 0.3

    private const val LINE_WIDTH_PX = 2.5f

    fun render(
        points: List<DecouplingTrendBuffer.Point>,
        deltaPct: Double?,
        windowSec: Int,
        widthPx: Int,
        heightPx: Int,
        marker: String = "",
        reuse: Bitmap? = null,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bmp = if (reuse != null && !reuse.isRecycled && reuse.width == w && reuse.height == h) {
            reuse
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val canvas = Canvas(bmp)

        // Background tracks the current drift so the field is colour-consistent with the
        // main decoupling field. Warm-up / empty buffer -> grey.
        val current = points.lastOrNull()?.driftPct
        val bg = if (current == null) DecouplingColors.WARMUP else DecouplingColors.forDrift(current)
        canvas.drawColor(bg)

        drawSparkline(canvas, points, windowSec, w, h)
        drawDeltaText(canvas, deltaPct, marker, w, h)

        return bmp
    }

    private fun drawSparkline(
        canvas: Canvas,
        points: List<DecouplingTrendBuffer.Point>,
        windowSec: Int,
        w: Int,
        h: Int,
    ) {
        if (points.size < 2) return

        val padX = w * 0.04f
        val padY = h * 0.10f
        val plotW = (w - 2 * padX).coerceAtLeast(1f)
        val plotH = (h - 2 * padY).coerceAtLeast(1f)

        val newest = points.last().movingSec
        val xStart = newest - windowSec
        val xSpan = (newest - xStart).coerceAtLeast(1).toFloat()

        var minD = points.minOf { it.driftPct }
        var maxD = points.maxOf { it.driftPct }
        // Pad the vertical range so a near-flat line sits mid-height rather than slamming
        // against an edge.
        val range = (maxD - minD)
        if (range < 1.0) {
            val mid = (maxD + minD) / 2.0
            minD = mid - 0.5
            maxD = mid + 0.5
        } else {
            val padD = range * 0.15
            minD -= padD
            maxD += padD
        }
        val dSpan = (maxD - minD).coerceAtLeast(1e-6)

        fun px(movingSec: Int) = padX + ((movingSec - xStart) / xSpan) * plotW
        fun py(drift: Double) = padY + (1.0 - (drift - minD) / dSpan).toFloat() * plotH

        // Faint zero-drift baseline if it falls within the plotted range.
        if (0.0 in minD..maxD) {
            val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = (Color.WHITE and 0x00FFFFFF) or (0x40 shl 24)
                strokeWidth = 1f
            }
            val zy = py(0.0)
            canvas.drawLine(padX, zy, padX + plotW, zy, zeroPaint)
        }

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = LINE_WIDTH_PX
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.WHITE
        }
        val path = Path()
        path.moveTo(px(points.first().movingSec), py(points.first().driftPct))
        for (i in 1 until points.size) {
            path.lineTo(px(points[i].movingSec), py(points[i].driftPct))
        }
        canvas.drawPath(path, linePaint)
    }

    private fun drawDeltaText(canvas: Canvas, deltaPct: Double?, marker: String, w: Int, h: Int) {
        val padX = w * 0.05f
        val padY = h * 0.06f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textSize = h * 0.30f
            style = Paint.Style.FILL
            setShadowLayer(h * 0.05f, 0f, 0f, Color.BLACK)
        }
        val text = if (deltaPct == null) {
            "—$marker"
        } else {
            val arrow = when {
                deltaPct > FLAT_THRESHOLD -> "▲"   // ▲
                deltaPct < -FLAT_THRESHOLD -> "▼"  // ▼
                else -> "▬"                          // ▬
            }
            String.format(Locale.US, "%s %+.1f%%%s", arrow, deltaPct, marker)
        }
        val bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
        // Top-left, so it doesn't collide with the most-recent (right) end of the line.
        val x = padX
        val y = padY + bounds.height()
        canvas.drawText(text, x, y, paint)
    }
}
