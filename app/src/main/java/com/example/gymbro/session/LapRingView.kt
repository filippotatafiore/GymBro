package com.example.gymbro.session

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.example.gymbro.R
import com.example.gymbro.themeColor
import kotlin.math.min

// Ring around the lap counter of a swim, drawn with Canvas (2D graphics): the arc in the favorite
// color is the part of the goal already swum, green when the goal is reached. It moves smoothly
// when laps are added. Without a goal: only the ring, in a light tint of the favorite color.
class LapRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val stroke = 10 * resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()

    private var hasGoal = false
    private var reached = false
    private var progress = 0f            // part of the goal drawn now, 0..1
    private var animator: ValueAnimator? = null

    // laps swum and goal (null = no goal); animate: the arc moves from the previous value
    fun show(laps: Int, goalLaps: Int?, animate: Boolean) {
        hasGoal = goalLaps != null
        reached = goalLaps != null && laps >= goalLaps
        val target = if (goalLaps != null) (laps.toFloat() / goalLaps).coerceIn(0f, 1f) else 0f
        animator?.cancel()
        if (animate && target != progress) {
            animator = ValueAnimator.ofFloat(progress, target).apply {
                duration = 450
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            progress = target
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = min(width, height) / 2f - stroke / 2
        val cx = width / 2f
        val cy = height / 2f
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        trackPaint.color = if (hasGoal) context.getColor(R.color.surfaceVariant) else context.themeColor(R.attr.accentSoftColor)
        canvas.drawCircle(cx, cy, radius, trackPaint)

        if (hasGoal && progress > 0f) {
            arcPaint.color = if (reached) context.getColor(R.color.successGreen) else context.themeColor(R.attr.accentColor)
            // from the top, clockwise
            canvas.drawArc(bounds, -90f, 360f * progress, false, arcPaint)
        }
    }
}
