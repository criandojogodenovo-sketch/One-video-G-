package com.onevideog.editor.editor

import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.OverlayTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * v0.2.5 regression: the preview effects-rebuild revision key must reflect
 * ONLY what the active clip's effect list visually depends on. The previous
 * key (project.overlays.hashCode()) rebuilt the whole effect chain on ANY
 * overlay edit anywhere — one avoidable decoder init per unrelated edit.
 */
class ClipEffectSignatureTest {

    private fun clip(id: String = "c1", durMs: Long = 10_000) = Clip(
        id = id, uri = "u", displayName = "d", sourceDurationMs = durMs,
        trimStartMs = 0, trimEndMs = durMs,
    )

    private fun overlay(
        id: String,
        start: Long,
        end: Long,
        z: Int = 0,
        transform: OverlayTransform = OverlayTransform(),
    ) = OverlayItem(
        id = id, kind = OverlayKind.TEXT, startMs = start, endMs = end,
        text = "hello", zOrder = z, transform = transform,
    )

    private fun project(
        clips: List<Clip> = listOf(clip()),
        overlays: List<OverlayItem> = emptyList(),
    ) = OvgProject(id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0)
        .copy(clips = clips, overlays = overlays)

    @Test
    fun `playhead is not an input - repeated evaluation is stable`() {
        val p = project(overlays = listOf(overlay("o1", 0, 10_000)))
        val s1 = ClipEffectPlan.effectiveSignature(p, p.clips[0], 0)
        val s2 = ClipEffectPlan.effectiveSignature(p, p.clips[0], 0)
        // A playhead move alone (no project change) can never rebuild: the
        // signature has no playhead parameter and is deterministic.
        assertEquals(s1, s2)
    }

    @Test
    fun `editing an overlay OUTSIDE this clip's window does not change the signature`() {
        // Empty overlays vs an overlay beyond the clip end: the WINDOW list is
        // empty in both cases, so the effective signature is identical even
        // though project.overlays.hashCode() differs (the old key rebuilt).
        val base = project(overlays = emptyList())
        val outside = base.copy(
            overlays = listOf(overlay("o1", 20_000, 25_000)),
        )
        val sBase = ClipEffectPlan.effectiveSignature(base, base.clips[0], 0)
        val sOutside = ClipEffectPlan.effectiveSignature(outside, outside.clips[0], 0)
        assertNotEquals(base.overlays.hashCode(), outside.overlays.hashCode())
        assertEquals(sBase, sOutside)
        // Adding an overlay INSIDE the window DOES change it.
        val inside = base.copy(overlays = listOf(overlay("o1", 0, 5_000)))
        assertNotEquals(
            sBase,
            ClipEffectPlan.effectiveSignature(inside, inside.clips[0], 0),
        )
        // Moving that overlay to another position inside the window also does.
        val moved = base.copy(overlays = listOf(overlay("o1", 7_000, 9_000)))
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(inside, inside.clips[0], 0),
            ClipEffectPlan.effectiveSignature(moved, moved.clips[0], 0),
        )
    }

    @Test
    fun `editing an intersecting overlay changes the signature`() {
        val base = project(overlays = listOf(overlay("o1", 0, 5_000)))
        val moved = base.copy(overlays = listOf(overlay("o1", 0, 5_000, transform = OverlayTransform(x = 0.7f))))
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(moved, moved.clips[0], 0),
        )
    }

    @Test
    fun `overlay on a DIFFERENT clip does not change this clip's signature`() {
        val first = clip("c1", 10_000)
        val second = clip("c2", 10_000)
        val base = project(clips = listOf(first, second))
        val withOverlayOnSecond = base.copy(
            overlays = listOf(overlay("o2", 10_000, 15_000)),
        )
        assertEquals(
            ClipEffectPlan.effectiveSignature(base, first, 0),
            ClipEffectPlan.effectiveSignature(withOverlayOnSecond, first, 0),
        )
        // ...but the second clip's own signature reacts.
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, second, 10_000),
            ClipEffectPlan.effectiveSignature(withOverlayOnSecond, second, 10_000),
        )
    }

    @Test
    fun `zOrder change reorders rendering and changes the signature`() {
        val base = project(
            overlays = listOf(overlay("a", 0, 5_000, z = 0), overlay("b", 0, 5_000, z = 1)),
        )
        val swapped = base.copy(
            overlays = listOf(overlay("a", 0, 5_000, z = 2), overlay("b", 0, 5_000, z = 1)),
        )
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(swapped, swapped.clips[0], 0),
        )
    }

    @Test
    fun `clip trim speed colour and aspect are signature inputs`() {
        val base = project()
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(base, base.clips[0].copy(trimEndMs = 8_000), 0),
        )
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(base, base.clips[0].copy(speed = 2f), 0),
        )
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(base, base.clips[0].copy(color = base.clips[0].color.copy(saturation = 1.5f)), 0),
        )
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(
                base.copy(aspect = com.onevideog.editor.data.ProjectAspect.R9_16.name),
                base.clips[0],
                0,
            ),
        )
    }

    @Test
    fun `null clip yields the empty signature`() {
        assertEquals(0, ClipEffectPlan.effectiveSignature(project(), null, 0))
    }
}
