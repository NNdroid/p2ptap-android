package app.fjj.p2ptap.tv.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.animation.AnimatorSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Shared focus-in / focus-out animation for every TV screen.
 *
 * Android TV's remote-driven focus model needs a visible scale change on
 * focus change — without it a focus jump from one item to a distant one
 * reads as "the highlight disappeared and reappeared somewhere else". The
 * Material Design TV guidance suggests 1.05–1.08x scale with 200–300ms
 * duration and an AccelerateDecelerate curve, which is what this does.
 *
 * Two scale factors are exposed because different screens want different
 * emphasis: the rail item scales 1.06x (subtle, it's a menu), while the
 * hero toggle scales 1.10x (prominent, it's the action).
 *
 * Cancel-on-new-focus is important: if a user pushes DPAD fast, three focus
 * events fire within 100ms. Without cancelAllPrevious, three concurrent
 * animations run and the last two fight the first, producing a visible
 * stutter at the halfway point of the target scale.
 */
object TvFocusAnimation {

    const val DURATION_MS = 200L
    const val SCALE_NORMAL = 1.00f
    const val SCALE_RAIL = 1.06f
    const val SCALE_CARD = 1.05f
    const val SCALE_BUTTON = 1.10f

    fun animate(view: View, toScale: Float, durationMs: Long = DURATION_MS) {
        // Cancel any in-flight animator on this view so a fast DPAD press
        // does not accumulate three overlapping animations.
        view.clearAnimation()

        val fromX = view.scaleX
        val fromY = view.scaleY
        val toX = toScale
        val toY = toScale

        if (fromX == toX && fromY == toY) return

        val xAnim = ObjectAnimator.ofFloat(view, "scaleX", fromX, toX)
        val yAnim = ObjectAnimator.ofFloat(view, "scaleY", fromY, toY)
        val set = AnimatorSet().apply {
            playTogether(xAnim, yAnim)
            interpolator = AccelerateDecelerateInterpolator()
            duration = durationMs
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    // Elevation transition: raising the view slightly while it
                    // scales up gives the "hovering" feel TVs use for focus.
                    view.elevation = if (toScale > SCALE_NORMAL) 8f else 0f
                }
            })
        }
        set.start()
    }

    fun attachTo(view: View, scale: Float = SCALE_CARD) {
        // Set an initial listener on the View itself, so any focus change
        // (from DPAD, from navigation, from a programmatic requestFocus)
        // triggers the animation. This is the shortest path to correct
        // focus behaviour across the whole app.
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnFocusChangeListener { v, hasFocus ->
            animate(v, if (hasFocus) scale else SCALE_NORMAL)
        }
    }
}
