package com.onevideog.editor.editor

import com.onevideog.editor.data.Interpolation
import com.onevideog.editor.data.Keyframe
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayTransform
import kotlin.math.roundToLong

/**
 * Evaluates an overlay's animated transform at a project time.
 *
 * Both the preview canvas and the export composition call this — there is no
 * second implementation, so keyframe behaviour cannot drift between the two.
 *
 * Semantics per animated property (x, y, scale, rotationDeg, opacity):
 *  - no defining keyframes  -> base [OverlayTransform] value
 *  - before first keyframe  -> first keyframe's value (hold)
 *  - after last keyframe    -> last keyframe's value (hold)
 *  - between keyframes      -> [Interpolation] curve between neighbours
 */
object KeyframeEvaluator {

    /** Linear interpolation. */
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Smooth 0..1 curve used by [Interpolation.EASE_IN_OUT] (smoothstep). */
    fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** Value of one property at [timeMs]; [defined] lists keyframes that set it. */
    private fun propertyAt(
        defined: List<Keyframe>,
        timeMs: Long,
        base: Float,
        interpolation: Interpolation,
        pick: (Keyframe) -> Float,
    ): Float {
        if (defined.isEmpty()) return base
        val first = defined.first()
        val last = defined.last()
        return when {
            timeMs <= first.timeMs -> pick(first)
            timeMs >= last.timeMs -> pick(last)
            else -> {
                var i = 0
                while (i < defined.size - 1 && defined[i + 1].timeMs <= timeMs) i++
                val a = defined[i]
                val b = defined[i + 1]
                val span = (b.timeMs - a.timeMs).coerceAtLeast(1)
                val u = (timeMs - a.timeMs).toFloat() / span
                val eased = if (interpolation == Interpolation.EASE_IN_OUT) smoothstep(u) else u
                lerp(pick(a), pick(b), eased)
            }
        }
    }

    /** Full transform of [overlay] at absolute project [timeMs]. */
    fun evaluate(overlay: OverlayItem, timeMs: Long): OverlayTransform {
        val kfs = overlay.keyframes.sortedBy { it.timeMs }
        val base = overlay.transform
        if (kfs.isEmpty()) return base.withClampedValues()
        val interpolation = overlay.interpolation
        fun defined(select: (Keyframe) -> Float?) =
            kfs.filter { select(it) != null }.map { it }
        val result = OverlayTransform(
            x = propertyAt(defined { it.x }, timeMs, base.x, interpolation) { it.x!! },
            y = propertyAt(defined { it.y }, timeMs, base.y, interpolation) { it.y!! },
            scale = propertyAt(defined { it.scale }, timeMs, base.scale, interpolation) { it.scale!! },
            rotationDeg = propertyAt(
                defined { it.rotationDeg }, timeMs, base.rotationDeg, interpolation,
            ) { it.rotationDeg!! },
            opacity = propertyAt(
                defined { it.opacity }, timeMs, base.opacity, interpolation,
            ) { it.opacity!! },
        )
        return result.withClampedValues()
    }

    /** Nearest editable keyframe slot time, snapped to [stepMs]. */
    fun snapTime(timeMs: Long, stepMs: Long = 50L): Long =
        (timeMs.toFloat() / stepMs).roundToLong() * stepMs
}
