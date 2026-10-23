package com.onevideog.editor.editor

import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.ClipEffect
import com.onevideog.editor.data.ClipTransition
import com.onevideog.editor.data.EffectKind
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.TransitionKind
import com.onevideog.editor.data.projectJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.0 (Fase I) — the per-clip effects contract:
 *  - JSON round-trip of the schema-v3 fields (clip.effects + project
 *    transitions) and backwards compatibility with v2 documents;
 *  - effectiveSignature: effects and transitions ARE signature inputs
 *    (they change the GL list — the decoder MUST re-prepare), overlays are
 *    NOT (the v0.3.1 contract is untouched);
 *  - the glue builds the expected effect instances (blur = two separable
 *    passes; identity intensities build nothing).
 */
class ClipEffectsTest {

    private fun clip(id: String = "c1", durMs: Long = 10_000, effects: List<ClipEffect> = emptyList()) = Clip(
        id = id, uri = "u", displayName = "d", sourceDurationMs = durMs,
        trimStartMs = 0, trimEndMs = durMs, effects = effects,
    )

    private fun project(
        clips: List<Clip>,
        transitions: List<ClipTransition> = emptyList(),
        overlays: List<OverlayItem> = emptyList(),
    ) = OvgProject(id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0)
        .copy(clips = clips, transitions = transitions, overlays = overlays)

    // ------------------------------------------------- serialization round-trip

    @Test
    fun `clip effects survive a JSON round trip`() {
        val source = clip(effects = listOf(ClipEffect(EffectKind.BLUR, 0.7f), ClipEffect(EffectKind.SHARPEN, 0.2f)))
        val json = projectJson.encodeToString(source)
        val decoded = projectJson.decodeFromString<Clip>(json)
        assertEquals(source, decoded)
    }

    @Test
    fun `project transitions survive a JSON round trip`() {
        val source = OvgProject(
            id = "p", name = "n", createdAtEpochMs = 1, updatedAtEpochMs = 2,
        ).copy(
            clips = listOf(clip("a"), clip("b")),
            transitions = listOf(
                ClipTransition(
                    id = "t1",
                    betweenClipIds = "a" to "b",
                    kind = TransitionKind.WIPE_LEFT,
                    durationMs = 800,
                ),
            ),
        )
        val json = projectJson.encodeToString(source)
        val decoded = projectJson.decodeFromString<OvgProject>(json)
        assertEquals(source, decoded)
        assertEquals(TransitionKind.WIPE_LEFT, decoded.transitions[0].kind)
        assertEquals(800L, decoded.transitions[0].durationMs)
    }

    @Test
    fun `a v2 document without the new fields decodes to schema v3 defaults`() {
        // A v0.5.x document: no "effects" on clips, no "transitions" on the
        // project. The schema-v3 defaults (empty) keep it loadable — and the
        // behaviour identical (no transitions, no effects).
        val v2Json = """
            {"schemaVersion":2,"id":"p","name":"n","createdAtEpochMs":1,"updatedAtEpochMs":2,
             "clips":[{"id":"c1","uri":"u","displayName":"d","sourceDurationMs":5000,
                       "trimStartMs":0,"trimEndMs":5000}],
             "overlays":[],"audioClips":[]}
        """.trimIndent()
        val decoded = projectJson.decodeFromString<OvgProject>(v2Json)
        assertTrue(decoded.clips[0].effects.isEmpty())
        assertTrue(decoded.transitions.isEmpty())
        // The stored schemaVersion is INFORMATIONAL and preserved as-is (2);
        // the new fields decode at their schema-v3 defaults (empty lists),
        // so a v2 document behaves exactly like a v3 one without them.
        assertEquals(2, decoded.schemaVersion)
    }

    // ---------------------------------------------------- effectiveSignature

    @Test
    fun `adding or changing a clip effect changes the signature`() {
        val base = project(listOf(clip()))
        val withBlur = project(listOf(clip(effects = listOf(ClipEffect(EffectKind.BLUR, 0.5f)))))
        val changedIntensity = project(listOf(clip(effects = listOf(ClipEffect(EffectKind.BLUR, 0.9f)))))
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(withBlur, withBlur.clips[0], 0),
        )
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(withBlur, withBlur.clips[0], 0),
            ClipEffectPlan.effectiveSignature(changedIntensity, changedIntensity.clips[0], 0),
        )
    }

    @Test
    fun `changing a transition changes the signature of every clip`() {
        val base = project(listOf(clip("a"), clip("b")))
        val withTransition = base.copy(
            transitions = listOf(ClipTransition(id = "t", betweenClipIds = "a" to "b")),
        )
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(withTransition, withTransition.clips[0], 0),
        )
        // The NEXT clip's list changes too (it carries the IN window).
        assertNotEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[1], 0),
            ClipEffectPlan.effectiveSignature(withTransition, withTransition.clips[1], 0),
        )
    }

    @Test
    fun `overlay edits still never change the signature - v0_3_1 contract intact`() {
        val base = project(
            listOf(clip()),
            overlays = listOf(
                OverlayItem(
                    id = "o1", kind = OverlayKind.TEXT, startMs = 0, endMs = 5_000, text = "x",
                ),
            ),
        )
        val movedOverlay = base.copy(
            overlays = listOf(
                base.overlays[0].copy(startMs = 1_000, endMs = 9_000),
            ),
        )
        assertEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(movedOverlay, movedOverlay.clips[0], 0),
        )
    }

    @Test
    fun `playhead position is still not an input`() {
        val base = project(listOf(clip()))
        assertEquals(
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 0),
            ClipEffectPlan.effectiveSignature(base, base.clips[0], 5_000),
        )
    }

    // ----------------------------------------------------- glue list building

    @Test
    fun `blur builds two separable passes and identity intensities build nothing`() {
        val twoPasses = ClipGlEffects.effectsFor(
            listOf(ClipEffect(EffectKind.BLUR, 0.5f)),
        )
        assertEquals(2, twoPasses.size)

        val zeroIntensity = ClipGlEffects.effectsFor(
            listOf(
                ClipEffect(EffectKind.BLUR, 0f),
                ClipEffect(EffectKind.VIGNETTE, 0f),
                ClipEffect(EffectKind.SHARPEN, 0f),
            ),
        )
        assertTrue(zeroIntensity.isEmpty())

        val singles = ClipGlEffects.effectsFor(
            listOf(ClipEffect(EffectKind.VIGNETTE, 0.5f), ClipEffect(EffectKind.SHARPEN, 0.5f)),
        )
        assertEquals(2, singles.size)
    }

    @Test
    fun `the glue trims to the max per clip`() {
        // 4 MODEL effects (the limit is 3): the 4th entry is trimmed before
        // building. BLUR = 2 passes, so a full 3-effect list builds exactly
        // 2 + 1 + 1 = 4 GL instances; a 4th BLUR would add 2 more.
        val overLimit = ClipGlEffects.effectsFor(
            listOf(
                ClipEffect(EffectKind.BLUR, 0.4f),
                ClipEffect(EffectKind.VIGNETTE, 0.4f),
                ClipEffect(EffectKind.SHARPEN, 0.4f),
                ClipEffect(EffectKind.BLUR, 0.6f),
            ),
        )
        assertEquals(4, overLimit.size)
        // The model-level limit constant stays in ONE place.
        assertEquals(3, ClipEffectsFactory.MAX_EFFECTS_PER_CLIP)
    }

    // ------------------------------------------- transition effects per clip

    @Test
    fun `transition effects attach to the tail of A and the head of B`() {
        val clips = listOf(clip("a"), clip("b"), clip("c"))
        val project = project(
            clips,
            transitions = listOf(
                ClipTransition(id = "t1", betweenClipIds = "a" to "b", durationMs = 500),
                ClipTransition(id = "t2", betweenClipIds = "b" to "c", durationMs = 400),
            ),
        )
        val onA = TransitionEffects.effectsForClip(project, clips[0], 0)
        assertEquals(1, onA.size) // OUT only
        val onB = TransitionEffects.effectsForClip(project, clips[1], 0)
        assertEquals(2, onB.size) // IN of t1 + OUT of t2
        val onC = TransitionEffects.effectsForClip(project, clips[2], 0)
        assertEquals(1, onC.size) // IN only
    }

    @Test
    fun `invalid transitions never produce effect instances`() {
        // 1 s clips: 900 ms is over the 50%-of-both rule -> the planner
        // discards the transition, so no effect is attached anywhere.
        val clips = listOf(clip("a", 1_000), clip("b", 1_000))
        val project = project(
            clips,
            transitions = listOf(
                ClipTransition(id = "t", betweenClipIds = "a" to "b", durationMs = 900),
            ),
        )
        assertTrue(TransitionEffects.effectsForClip(project, clips[0], 0).isEmpty())
        assertTrue(TransitionEffects.effectsForClip(project, clips[1], 0).isEmpty())
    }
}
