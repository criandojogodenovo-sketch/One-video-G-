package com.onevideog.editor.editor

import com.onevideog.editor.data.Keyframe
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.OverlayTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5.0 (Fase F) — pure auto-key / navigation / collision rules:
 *
 *  - F1 applyTransformEdit: no keyframes -> base transform; keyframe within
 *    tolerance -> updated IN PLACE (exact time kept); between keyframes ->
 *    new keyframe at the snapped playhead. Pure: the input overlay is never
 *    mutated (the ViewModel commits the result — one undo step per edit).
 *  - F2 resolveMoveTarget: nearest FREE 50 ms slot, clamp at 0, REJECT
 *    (null) when the search radius is exhausted — a move can never delete
 *    the keyframe at the target (pre-v0.5.0 data-loss bug).
 *  - F3/F4 navigation + on-keyframe detection.
 */
class KeyframeEditingTest {

    private fun overlay(
        keyframes: List<Keyframe> = emptyList(),
        transform: OverlayTransform = OverlayTransform(),
    ) = OverlayItem(
        id = "ov",
        kind = OverlayKind.TEXT,
        startMs = 0,
        endMs = 10_000,
        transform = transform,
        text = "hello",
        keyframes = keyframes,
    )

    private fun kf(timeMs: Long, x: Float = 0.1f) =
        Keyframe(timeMs = timeMs, x = x, y = 0.5f, scale = 0.6f, rotationDeg = 0f, opacity = 1f)

    // ---- F1: auto-key ------------------------------------------------------

    @Test
    fun `no keyframes writes the base transform`() {
        val ov = overlay(transform = OverlayTransform(x = 0.2f))
        val out = KeyframeEditing.applyTransformEdit(
            ov, playheadMs = 5_000,
            newTransform = OverlayTransform(x = 0.9f, y = 0.1f),
        )
        assertTrue(out.keyframes.isEmpty())
        assertEquals(0.9f, out.transform.x, 1e-6f)
        assertEquals(0.1f, out.transform.y, 1e-6f)
    }

    @Test
    fun `edit within tolerance updates the keyframe in place keeping its exact time`() {
        val ov = overlay(keyframes = listOf(kf(1_000, x = 0.1f)))
        val out = KeyframeEditing.applyTransformEdit(
            ov, playheadMs = 1_015,
            newTransform = OverlayTransform(x = 0.7f, y = 0.3f),
        )
        assertEquals(1, out.keyframes.size)
        assertEquals(1_000L, out.keyframes[0].timeMs) // exact time preserved
        assertEquals(0.7f, out.keyframes[0].x!!, 1e-6f)
        assertEquals(0.3f, out.keyframes[0].y!!, 1e-6f)
    }

    @Test
    fun `edit between keyframes creates a new keyframe at the snapped playhead`() {
        val ov = overlay(keyframes = listOf(kf(1_000), kf(3_000)))
        val out = KeyframeEditing.applyTransformEdit(
            ov, playheadMs = 1_980,
            newTransform = OverlayTransform(x = 0.4f),
        )
        assertEquals(3, out.keyframes.size)
        val created = out.keyframes.first { it.timeMs == 2_000L }
        assertEquals(0.4f, created.x!!, 1e-6f)
    }

    @Test
    fun `edit exactly on an existing time replaces its values (no duplicate)`() {
        val ov = overlay(keyframes = listOf(kf(1_000, x = 0.1f)))
        val out = KeyframeEditing.applyTransformEdit(
            ov, playheadMs = 1_000,
            newTransform = OverlayTransform(x = 0.8f),
        )
        assertEquals(1, out.keyframes.size)
        assertEquals(0.8f, out.keyframes[0].x!!, 1e-6f)
    }

    @Test
    fun `auto-key is pure - the input overlay is unchanged`() {
        val ov = overlay(keyframes = listOf(kf(1_000)))
        val out = KeyframeEditing.applyTransformEdit(
            ov, playheadMs = 1_000,
            newTransform = OverlayTransform(x = 0.8f),
        )
        assertNotEquals(out, ov)
        assertEquals(0.1f, ov.keyframes[0].x!!, 1e-6f)
    }

    @Test
    fun `auto-key sanitizes non-finite values through withClampedValues`() {
        val ov = overlay()
        val out = KeyframeEditing.applyTransformEdit(
            ov, playheadMs = 1_000,
            newTransform = OverlayTransform(x = Float.NaN, scale = Float.POSITIVE_INFINITY),
        )
        assertEquals(0.5f, out.transform.x, 1e-6f)
        assertEquals(0.6f, out.transform.scale, 1e-6f)
    }

    // ---- F2: move collision rule -------------------------------------------

    @Test
    fun `move to a free slot lands on the snapped time`() {
        val kfs = listOf(kf(1_000), kf(3_000))
        assertEquals(
            1_550L,
            KeyframeEditing.resolveMoveTarget(kfs, fromMs = 1_000, requestedMs = 1_530),
        )
    }

    @Test
    fun `move onto another keyframe lands on the nearest free slot - not a delete`() {
        val kfs = listOf(kf(1_000), kf(2_000))
        // 2000 occupied: +50 (2050) and -50 (1950) both free -> prefer later.
        assertEquals(
            2_050L,
            KeyframeEditing.resolveMoveTarget(kfs, fromMs = 1_000, requestedMs = 2_010),
        )
    }

    @Test
    fun `move prefers the free side when one neighbour is occupied`() {
        val kfs = listOf(kf(1_000), kf(2_000), kf(2_050))
        // Target 2000 occupied; +50 (2050) also occupied; -50 (1950) free.
        assertEquals(
            1_950L,
            KeyframeEditing.resolveMoveTarget(kfs, fromMs = 1_000, requestedMs = 2_000),
        )
    }

    @Test
    fun `move is rejected when every slot in the search radius is occupied`() {
        val kfs = (1_800L..2_200L step 50).map { kf(it) } + kf(1_000)
        assertNull(
            KeyframeEditing.resolveMoveTarget(kfs, fromMs = 1_000, requestedMs = 2_000),
        )
    }

    @Test
    fun `move never lands on a negative time`() {
        val kfs = listOf(kf(0), kf(1_000))
        // snapped(-30) = -50 -> invalid; up candidate 0 occupied; next +50.
        assertEquals(
            50L,
            KeyframeEditing.resolveMoveTarget(kfs, fromMs = 1_000, requestedMs = -30),
        )
    }

    @Test
    fun `moving onto the source slot itself is a no-op target`() {
        val kfs = listOf(kf(1_000), kf(2_000))
        assertEquals(
            1_000L,
            KeyframeEditing.resolveMoveTarget(kfs, fromMs = 1_000, requestedMs = 1_010),
        )
    }

    // ---- F3: navigation ------------------------------------------------------

    @Test
    fun `previous and next skip the keyframe the playhead sits on`() {
        val kfs = listOf(kf(1_000), kf(2_000), kf(3_000))
        val prev = KeyframeEditing.previousKeyframe(kfs, 2_000)
        val next = KeyframeEditing.nextKeyframe(kfs, 2_000)
        assertEquals(1_000L, prev?.timeMs)
        assertEquals(3_000L, next?.timeMs)
    }

    @Test
    fun `previous and next at the window edges`() {
        val kfs = listOf(kf(1_000), kf(2_000))
        assertNull(KeyframeEditing.previousKeyframe(kfs, 1_000))
        assertNull(KeyframeEditing.nextKeyframe(kfs, 2_000))
        assertEquals(2_000L, KeyframeEditing.nextKeyframe(kfs, 1_000)?.timeMs)
    }

    @Test
    fun `between keyframes navigation returns the neighbours`() {
        val kfs = listOf(kf(1_000), kf(3_000))
        assertEquals(1_000L, KeyframeEditing.previousKeyframe(kfs, 2_000)?.timeMs)
        assertEquals(3_000L, KeyframeEditing.nextKeyframe(kfs, 2_000)?.timeMs)
    }

    // ---- F4: on-keyframe indicator -------------------------------------------

    @Test
    fun `keyframeAtPlayhead uses the half-snap tolerance`() {
        val kfs = listOf(kf(1_000))
        assertEquals(1_000L, KeyframeEditing.keyframeAt(kfs, 1_020)?.timeMs)
        assertNull(KeyframeEditing.keyframeAt(kfs, 1_040))
        assertNull(KeyframeEditing.keyframeAt(emptyList(), 1_000))
        assertTrue(kf(1_000).let { KeyframeEditing.keyframeAt(listOf(it), 999) != null })
    }
}
