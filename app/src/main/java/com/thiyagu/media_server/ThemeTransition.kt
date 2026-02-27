package com.thiyagu.media_server

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

data class ThemeWavePalette(
    val primary: Int,
    val background: Int
)

object ThemeTransitionController {
    private var pendingPalette: ThemeWavePalette? = null

    fun prepareTransition(context: Context) {
        val primary = ContextCompat.getColor(context, R.color.lanflix_primary)
        val background = ContextCompat.getColor(context, R.color.lanflix_bg)
        pendingPalette = ThemeWavePalette(primary, background)
    }

    fun attachIfPending(activity: Activity) {
        val palette = pendingPalette ?: return
        pendingPalette = null
        val decorView = activity.window.decorView as? ViewGroup ?: return
        val overlay = ThemeWaveOverlayView(activity, palette)
        decorView.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        overlay.start {
            decorView.removeView(overlay)
        }
    }
}

class ThemeWaveOverlayView(
    context: Context,
    private val palette: ThemeWavePalette
) : View(context) {

    private val wavePath = Path()
    private val fillPath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val waveHeightPx = 18f * resources.displayMetrics.density
    private var progress = 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun start(onEnd: () -> Unit) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 720L
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd()
                }

                override fun onAnimationCancel(animation: Animator) {
                    onEnd()
                }
            })
        }
        post { animator?.start() }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return
        val clamped = progress.coerceIn(0f, 1f)
        if (clamped >= 1f) return
        val waveY = height * clamped
        val segmentWidth = width / 4f

        wavePath.reset()
        wavePath.moveTo(0f, waveY)
        var currentX = 0f
        var direction = 1f
        repeat(4) {
            val midX = currentX + segmentWidth / 2f
            val endX = currentX + segmentWidth
            val controlY = waveY + (waveHeightPx * direction)
            wavePath.quadTo(midX, controlY, endX, waveY)
            currentX = endX
            direction *= -1f
        }

        fillPath.reset()
        fillPath.addPath(wavePath)
        fillPath.lineTo(width, height)
        fillPath.lineTo(0f, height)
        fillPath.close()

        val startY = (waveY - waveHeightPx * 2f).coerceAtLeast(0f)
        val gradient = LinearGradient(
            0f,
            startY,
            0f,
            height,
            intArrayOf(
                adjustAlpha(palette.primary, 0.28f),
                adjustAlpha(palette.background, 0.98f),
                palette.background
            ),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        paint.style = Paint.Style.FILL
        canvas.drawPath(fillPath, paint)

        strokePaint.shader = null
        strokePaint.color = adjustAlpha(palette.primary, 0.4f)
        strokePaint.strokeWidth = waveHeightPx * 0.18f
        canvas.drawPath(wavePath, strokePaint)
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}
