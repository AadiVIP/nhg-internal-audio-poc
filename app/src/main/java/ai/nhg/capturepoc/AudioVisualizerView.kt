package ai.nhg.capturepoc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 32
    private val barHeights = FloatArray(barCount) { 0.05f }
    private val targetHeights = FloatArray(barCount) { 0.05f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E676.toInt()
    }
    private val barRect = RectF()
    private val random = Random(42)

    var accentColor: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    fun updateLevel(rmsDb: Double, peakDb: Double, isRecording: Boolean) {
        if (!isRecording || rmsDb <= -90.0) {
            for (i in 0 until barCount) {
                targetHeights[i] = 0.04f
            }
        } else {
            // Normalize RMS from -60 dBFS to 0 dBFS into 0.1 .. 1.0
            val normalized = ((rmsDb + 60.0) / 60.0).coerceIn(0.05, 1.0).toFloat()
            val peakNorm = ((peakDb + 50.0) / 50.0).coerceIn(0.1, 1.0).toFloat()

            for (i in 0 until barCount) {
                // Bell curve shape centered in the middle + slight organic variation
                val distFromCenter = kotlin.math.abs(i - (barCount / 2f)) / (barCount / 2f)
                val curve = (1.0f - distFromCenter * 0.45f).coerceIn(0.2f, 1.0f)
                val variation = 0.75f + (random.nextFloat() * 0.5f)
                val target = (normalized * curve * variation * peakNorm).coerceIn(0.06f, 0.98f)
                targetHeights[i] = target
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val totalGapRatio = 0.35f
        val barWidth = (w * (1f - totalGapRatio)) / barCount
        val gap = (w * totalGapRatio) / (barCount + 1)
        val radius = barWidth / 2f

        var x = gap
        val centerY = h / 2f

        for (i in 0 until barCount) {
            // Smooth lerp decay toward target height
            if (barHeights[i] < targetHeights[i]) {
                barHeights[i] = min(targetHeights[i], barHeights[i] + 0.22f)
            } else {
                barHeights[i] = max(targetHeights[i], barHeights[i] - 0.08f)
            }

            val curH = (barHeights[i] * h).coerceAtLeast(barWidth * 0.8f)
            val top = (centerY - curH / 2f).coerceAtLeast(0f)
            val bottom = (centerY + curH / 2f).coerceAtMost(h)

            barRect.set(x, top, x + barWidth, bottom)
            canvas.drawRoundRect(barRect, radius, radius, paint)

            x += barWidth + gap
        }
    }
}
