package com.onevideog.editor.editor

import com.onevideog.editor.data.Interpolation
import com.onevideog.editor.data.Keyframe
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.OverlayTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeEvaluatorTest {

    private fun overlay(
        transform: OverlayTransform = OverlayTransform(),
        keyframes: List<Keyframe> = emptyList(),
        interpolation: Interpolation = Interpolation.LINEAR,
    ) = OverlayItem(
        id = "o1",
        kind = OverlayKind.TEXT,
        text = "hi",
        startMs = 0,
        endMs = 10_000,
        transform = transform,
        keyframes = keyframes,
        interpolation = interpolation,
    )

    @Test
    fun `no keyframes returns base transform`() {
        val base = OverlayTransform(x = 0.3f, y = 0.7f, scale = 0.5f, rotationDeg = 12f, opacity = 0.8f)
        val result = KeyframeEvaluator.evaluate(overlay(base), 5_000)
        assertEquals(base, result)
    }

    @Test
    fun `holds first keyframe value before it and last after`() {
        val o = overlay(
            keyframes = listOf(
                Keyframe(timeMs = 2_000, x = 0.1f),
                Keyframe(timeMs = 8_000, x = 0.9f),
            ),
        )
        assertEquals(0.1f, KeyframeEvaluator.evaluate(o, 0).x, 1e-4f)
        assertEquals(0.1f, KeyframeEvaluator.evaluate(o, 1_000).x, 1e-4f)
        assertEquals(0.9f, KeyframeEvaluator.evaluate(o, 9_000).x, 1e-4f)
    }

    @Test
    fun `linear interpolation between keyframes`() {
        val o = overlay(
            keyframes = listOf(
                Keyframe(timeMs = 0, x = 0f),
                Keyframe(timeMs = 1_000, x = 1f),
            ),
        )
        assertEquals(0.25f, KeyframeEvaluator.evaluate(o, 250).x, 1e-4f)
        assertEquals(0.5f, KeyframeEvaluator.evaluate(o, 500).x, 1e-4f)
        assertEquals(0.75f, KeyframeEvaluator.evaluate(o, 750).x, 1e-4f)
    }

    @Test
    fun `ease in out is slower at the edges than linear`() {
        val linear = overlay(
            keyframes = listOf(Keyframe(0, x = 0f), Keyframe(1_000, x = 1f)),
            interpolation = Interpolation.LINEAR,
        )
        val smooth = overlay(
            keyframes = listOf(Keyframe(0, x = 0f), Keyframe(1_000, x = 1f)),
            interpolation = Interpolation.EASE_IN_OUT,
        )
        val a = KeyframeEvaluator.evaluate(linear, 250).x
        val b = KeyframeEvaluator.evaluate(smooth, 250).x
        assertTrue("smooth should lag linear near the start", b < a)
        // Endpoints agree.
        assertEquals(0f, KeyframeEvaluator.evaluate(smooth, 0).x, 1e-4f)
        assertEquals(1f, KeyframeEvaluator.evaluate(smooth, 1_000).x, 1e-4f)
    }

    @Test
    fun `keyframes only define the properties they set`() {
        val o = overlay(
            transform = OverlayTransform(x = 0.5f, y = 0.5f, scale = 1f, opacity = 1f),
            keyframes = listOf(Keyframe(0, scale = 0.2f), Keyframe(1_000, scale = 0.8f)),
        )
        val mid = KeyframeEvaluator.evaluate(o, 500)
        assertEquals(0.5f, mid.scale, 1e-4f)
        assertEquals(0.5f, mid.x, 1e-4f) // untouched by keyframes
        assertEquals(1f, mid.opacity, 1e-4f)
    }

    @Test
    fun `animates all five properties together`() {
        val o = overlay(
            keyframes = listOf(
                Keyframe(0, x = 0f, y = 0f, scale = 0.1f, rotationDeg = 0f, opacity = 0f),
                Keyframe(1_000, x = 1f, y = 1f, scale = 1f, rotationDeg = 90f, opacity = 1f),
            ),
        )
        val mid = KeyframeEvaluator.evaluate(o, 500)
        assertEquals(0.5f, mid.x, 1e-4f)
        assertEquals(0.5f, mid.y, 1e-4f)
        assertEquals(0.55f, mid.scale, 1e-4f)
        assertEquals(45f, mid.rotationDeg, 1e-3f)
        assertEquals(0.5f, mid.opacity, 1e-4f)
    }

    @Test
    fun `evaluated transform stays within valid ranges`() {
        val o = overlay(
            keyframes = listOf(
                Keyframe(0, x = -5f, y = 9f, scale = 99f, opacity = 4f),
                Keyframe(1_000, x = 5f, y = -9f, scale = -3f, opacity = -4f),
            ),
        )
        for (t in listOf(0L, 250L, 500L, 1_000L)) {
            val r = KeyframeEvaluator.evaluate(o, t)
            assertTrue(r.x in 0f..1f)
            assertTrue(r.y in 0f..1f)
            assertTrue(r.scale in 0.05f..1.6f)
            assertTrue(r.opacity in 0f..1f)
        }
    }
}
