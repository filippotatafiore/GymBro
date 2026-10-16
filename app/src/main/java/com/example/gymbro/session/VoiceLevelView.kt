package com.example.gymbro.session

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.gymbro.R
import com.example.gymbro.themeColor
import kotlin.math.abs
import kotlin.math.min

// The microphone of the voice counting (swim page), drawn with Canvas (2D graphics):
// - WAITING: a circle with the microphone; around it a soft ring that grows with the loudness of
//   the sound near the phone (it shows that the phone hears)
// - LISTENING: the circle in the favorite color, with waves that expand from it (animated): the
//   phone is listening to the number of laps
// - OFF, NO_PERMISSION, UNAVAILABLE: grey, with the microphone crossed out
class VoiceLevelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * resources.displayMetrics.density
    }
    private val micIcon = ContextCompat.getDrawable(context, R.drawable.mic_icon)!!.mutate()
    private val micOffIcon = ContextCompat.getDrawable(context, R.drawable.mic_off_icon)!!.mutate()

    var state = VoiceState.OFF
        set(value) {
            field = value
            invalidate()
        }

    // Loudness 0..1 (WAITING): the ring follows it smoothly
    var level = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    private var shownLevel = 0f

    override fun onDraw(canvas: Canvas) {
        val outer = min(width, height) / 2f
        val inner = outer * 0.62f
        val cx = width / 2f
        val cy = height / 2f
        val accent = context.themeColor(R.attr.accentColor)

        when (state) {
            VoiceState.WAITING -> {
                shownLevel += (level - shownLevel) * 0.35f
                fill.color = context.themeColor(R.attr.accentSoftColor)
                canvas.drawCircle(cx, cy, inner + (outer - inner) * shownLevel, fill)
                fill.color = context.getColor(R.color.surfaceVariant)
                canvas.drawCircle(cx, cy, inner, fill)
                // keeps moving until the ring reaches the loudness
                if (abs(level - shownLevel) > 0.01f) postInvalidateOnAnimation()
            }
            VoiceState.LISTENING -> {
                // two waves, one every 0.6 s, that grow and fade
                val time = SystemClock.uptimeMillis() % 1200 / 1200f
                for (offset in listOf(0f, 0.5f)) {
                    val t = (time + offset) % 1f
                    wave.color = accent
                    wave.alpha = ((1 - t) * 160).toInt()
                    canvas.drawCircle(cx, cy, inner + (outer - inner) * t, wave)
                }
                fill.color = accent
                canvas.drawCircle(cx, cy, inner, fill)
                postInvalidateOnAnimation()
            }
            else -> {
                fill.color = context.getColor(R.color.surfaceVariant)
                canvas.drawCircle(cx, cy, inner, fill)
            }
        }

        val icon = if (state == VoiceState.WAITING || state == VoiceState.LISTENING) micIcon else micOffIcon
        icon.setTint(when (state) {
            VoiceState.LISTENING -> context.themeColor(R.attr.onAccentColor)
            VoiceState.WAITING -> context.themeColor(R.attr.accentTextColor)
            else -> context.getColor(R.color.textSecondary)
        })
        val half = (inner * 0.55f).toInt()
        icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        icon.draw(canvas)
    }
}
