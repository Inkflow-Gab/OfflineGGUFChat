package com.offlinegguf.chat.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring

/**
 * Every transition in the app comes from this one object.
 *
 * The rule the timings follow: nothing the user is *waiting on* takes longer than
 * ~220 ms, nothing that carries *meaning* — a message arriving, a diff revealing
 * itself — is shorter than ~320 ms, and nothing exceeds that. Curves decelerate
 * on the way in and accelerate on the way out, so elements arrive and leave
 * instead of bouncing.
 *
 * Call sites compose the tokens with `tween(...)` directly:
 * `animateFloatAsState(target, tween(motion.fast, easing = motion.easeDecelerate))`.
 *
 * Under the "Reduce motion" preference every duration is 0, so those same call
 * sites produce clean cuts without knowing the setting exists. [reduced] is
 * exposed only for the places where an animation should not be *started* at all —
 * the caret's infinite blink is the one example.
 */
class MotionSpec(
    /** Press feedback, chip toggles, gauge needles, height easing. */
    val fast: Int = 150,
    /** Default for everything: fades, slides, expand/collapse. */
    val standard: Int = 220,
    /** Message entry, sheet settle, navigation pushes. */
    val slow: Int = 320,
    /** True when the user asked for less motion; every duration is then 0. */
    val reduced: Boolean = false,
) {
    /** Material "standard": quick start, gentle landing. */
    val easeStandard: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /** Elements travelling a long distance: navigation pushes, sheets. */
    val easeEmphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** Entering elements: fast in, soft stop. */
    val easeDecelerate: Easing = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)

    /** Exiting elements: no wind-up, straight out. */
    val easeAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** Streaming caret: a slow blink reads calmer than a fast one. 0 means "do not blink". */
    val caretBlink: Int get() = if (reduced) 0 else 760

    /**
     * Springs for the *playful* layer: tab icons, chips, like hearts. Tweens
     * (above) still drive anything the user is waiting on; springs only decorate
     * state changes that have already happened, so a bouncy settle never delays
     * feedback. Under Reduce motion both collapse to [snap].
     */
    val springBouncy: AnimationSpec<Float>
        get() = if (reduced) snap() else spring(
            dampingRatio = 0.55f,
            stiffness = Spring.StiffnessMediumLow,
        )

    /** Settles without overshoot — indicator pills, gauges, progress. */
    val springSmooth: AnimationSpec<Float>
        get() = if (reduced) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        )

    companion object {
        /** The spec to install for a given "Reduce motion" preference. */
        fun forPreference(reduceMotion: Boolean): MotionSpec =
            if (reduceMotion) MotionSpec(0, 0, 0, reduced = true) else MotionSpec()
    }
}
