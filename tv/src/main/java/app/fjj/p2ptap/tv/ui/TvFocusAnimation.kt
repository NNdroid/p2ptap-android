package app.fjj.p2ptap.tv.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import app.fjj.p2ptap.tv.R

/**
 * Shared focus-in / focus-out treatment for every TV screen.
 *
 * Two things changed in the 10-foot redesign, and they are worth stating
 * because the old behaviour looked "fine" in code review:
 *
 * 1. **Scale alone is not a focus indicator.** The previous version animated
 *    to 1.06x and raised elevation to 8dp. On a dark panel at three metres a
 *    6% scale is below the perceptual threshold once the room lights are down,
 *    and elevation produces no visible effect at all against a near-black
 *    background — there is nothing to cast a shadow onto. A user pressing DPAD
 *    could not tell which row the remote was pointing at. Cards now also get a
 *    3dp [R.color.md_theme_primary] ring via [attachToCard], and rail items
 *    swap to a ring drawable in [TvNavRailAdapter]. Scale is kept, but only as
 *    reinforcement.
 *
 * 2. **160 ms, not 200 ms.** Remote-driven input arrives in bursts; a user
 *    holding DPAD generates a focus change every ~80 ms. At 200 ms each
 *    animation is cut off before it lands and the row looks like it is
 *    twitching. 160 ms finishes inside the inter-key interval, so fast
 *    scrolling reads as a crisp march instead of a stutter.
 *
 * Cancel-on-new-focus is still required: without clearing the in-flight
 * animator, three overlapping ObjectAnimators fight over scaleX/scaleY and the
 * view visibly jitters at the halfway point.
 */
object TvFocusAnimation {

    const val DURATION_MS = 160L
    const val SCALE_NORMAL = 1.00f
    const val SCALE_RAIL = 1.03f
    const val SCALE_CARD = 1.035f
    const val SCALE_BUTTON = 1.045f

    fun animate(view: View, toScale: Float, durationMs: Long = DURATION_MS) {
        view.clearAnimation()

        val fromX = view.scaleX
        val fromY = view.scaleY
        if (fromX == toScale && fromY == toScale) return

        val xAnim = ObjectAnimator.ofFloat(view, "scaleX", fromX, toScale)
        val yAnim = ObjectAnimator.ofFloat(view, "scaleY", fromY, toScale)
        val set = AnimatorSet().apply {
            playTogether(xAnim, yAnim)
            interpolator = AccelerateDecelerateInterpolator()
            duration = durationMs
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    view.elevation = if (toScale > SCALE_NORMAL) 6f else 0f
                }
            })
        }
        set.start()
    }

    /** Scale-only focus for views that already draw their own focus state. */
    fun attachTo(view: View, scale: Float = SCALE_CARD) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnFocusChangeListener { v, hasFocus ->
            animate(v, if (hasFocus) scale else SCALE_NORMAL)
        }
    }

    /**
     * Focus for a [MaterialCardView]: scale **and** a ring.
     *
     * [MaterialCardView.setStrokeWidth] draws inside the card bounds, so the
     * ring costs no layout pass and cannot clip against a parent. The base
     * stroke is restored on blur so an unfocused card keeps its hairline.
     */
    fun attachToCard(card: MaterialCardView, scale: Float = SCALE_CARD) {
        val res = card.resources
        val ringPx = res.getDimensionPixelSize(R.dimen.tv_focus_ring)
        val basePx = res.getDimensionPixelSize(R.dimen.tv_card_stroke)
        val ringColor = ContextCompat.getColor(card.context, R.color.md_theme_focusRing)
        val baseColor = ContextCompat.getColor(card.context, R.color.tv_card_stroke)

        card.isFocusable = true
        card.isFocusableInTouchMode = true
        card.strokeWidth = basePx
        card.strokeColor = baseColor

        card.setOnFocusChangeListener { v, hasFocus ->
            animate(v, if (hasFocus) scale else SCALE_NORMAL)
            card.strokeWidth = if (hasFocus) ringPx else basePx
            card.strokeColor = if (hasFocus) ringColor else baseColor
        }
    }
}
