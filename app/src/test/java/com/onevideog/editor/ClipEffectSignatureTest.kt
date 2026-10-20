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
 * Preview effects-rebuild revision key contract.
 *
 * v0.2.5: the key must reflect ONLY what the active clip's effect list
 * visually depends on (the previous key, project.overlays.hashCode(), rebuilt
 * the whole effect chain on ANY overlay edit anywhere).
 *
 * v0.3.1 revision: the preview effect list no longer contains the
 * OverlayEffect — overlay pixels are drawn by the Compose layer, which
 * re-renders from project state directly. Overlays are therefore NOT
 * signature inputs at all: editing, adding, removing or reordering overlays
 * must never re-prepare the preview decoder (previously one decoder init +
 * frame flash per overlay edit). Clip identity/trim/speed/colour/aspect
 * remain inputs.
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
    fun `editing an overlay INSIDE the clip window does not change the signature`() {
        // v0.3.1: overlay pixels are drawn by the Compose layer from project
        // state — an overlay edit must never re-prepare the decoder.
        val base = project(overlays = listOf(overlay("o1", 0, 5_000)))
        val moved = base.copy(
            overlays = listOf(overlay("o1", 0, 5_000, transform = OverlayTransform(x = 0.7f))),
        )
        assertEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(moved, base.clips[0], 0),
        )
    }

    @Test
    fun `adding removing or reordering overlays does not change the signature`() {
        val base = project(overlays = emptyList())
        val added = base.copy(
            overlays = listOf(overlay("o1", 0, 5_000), overlay("o2", 2_000, 8_000, z = 1)),
        )
        assertEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(added, added.clips[0], 0),
        )
        // Removing all overlays: same story — the decoder has nothing to
        // re-apply for overlay-only changes.
        assertEquals(
            ClipEffectPlan.effectiveSignature(added, added.clips[0], 0),
            ClipEffectPlan.effectiveSignature(base, added.clips[0], 0),
        )
    }

    @Test
    fun `overlay edits on ANY clip never change another clip's signature`() {
        val first = clip("c1", 10_000)
        val second = clip("c2", 10_000)
        val base = project(clips = listOf(first, second))
        val withOverlays = base.copy(
            overlays = listOf(overlay("o2", 10_000, 15_000)),
        )
        assertEquals(
            ClipEffectPlan.effectiveSignature(base, first, 0),
            ClipEffectPlan.effectiveSignature(withOverlays, first, 0),
        )
    }

    @Test
    fun `out-of-range overlay edits do not change the signature`() {
        // v0.2.5 rule, still true under the v0.3.1 contract.
        val base = project(overlays = listOf(overlay("o1", 20_000, 25_000)))
        val edited = base.copy(overlays = listOf(overlay("o1", 21_000, 26_000)))
        assertEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(edited, edited.clips[0], 0),
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
