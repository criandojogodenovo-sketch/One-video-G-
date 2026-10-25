package com.onevideog.editor.editor

import com.onevideog.editor.data.AudioClipItem
import com.onevideog.editor.data.AudioKeyframe
import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.ClipEffect
import com.onevideog.editor.data.ClipTransition
import com.onevideog.editor.data.ColorCurves
import com.onevideog.editor.data.Curve
import com.onevideog.editor.data.CurvePoint
import com.onevideog.editor.data.EffectKind
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.OverlayItem
import com.onevideog.editor.data.OverlayKind
import com.onevideog.editor.data.SpeedPoint
import com.onevideog.editor.data.TransitionKind
import com.onevideog.editor.data.projectJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.0 (Fase I) — the per-clip effects contract:
 *  - JSON round-trip of the schema-v3 fields (clip.effects + project
 *    transitions) and backwards compatibility with v2 documents;
 *  - effectiveSignature: effects and transitions ARE signature inputs
 *    (they change the GL list — the decoder MUST re-prepare), overlays are
 *    NOT (the v0.3.1 contract is untouched);
 *  - the glue builds the expected effect instances (blur = ONE effect
 *    carrying both separable passes since v0.9.1; identity intensities
 *    build nothing).
 */
class ClipEffectsTest {

    private fun clip(
        id: String = "c1",
        durMs: Long = 10_000,
        effects: List<ClipEffect> = emptyList(),
        curves: ColorCurves? = null,
        ramps: List<SpeedPoint> = emptyList(),
    ) = Clip(
        id = id, uri = "u", displayName = "d", sourceDurationMs = durMs,
        trimStartMs = 0, trimEndMs = durMs, effects = effects,
        curves = curves, speedRamps = ramps,
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

    // ------------------------------------------------- v0.7.0 (schema v4)

    @Test
    fun `a v3 document decodes to schema v4 defaults - curves null and ramps empty`() {
        // A v0.6.0 document: no "curves" on clips, no "speedRamps". The v4
        // defaults (null / emptyList) keep it loadable and byte-compatible —
        // the v3 behaviour exactly (no curves, constant speed).
        val v3Json = """
            {"schemaVersion":3,"id":"p","name":"n","createdAtEpochMs":1,"updatedAtEpochMs":2,
             "clips":[{"id":"c1","uri":"u","displayName":"d","sourceDurationMs":5000,
                       "trimStartMs":0,"trimEndMs":5000}],
             "overlays":[],"audioClips":[],"transitions":[]}
        """.trimIndent()
        val decoded = projectJson.decodeFromString<OvgProject>(v3Json)
        assertEquals(3, decoded.schemaVersion)
        assertNull(decoded.clips[0].curves)
        assertTrue(decoded.clips[0].speedRamps.isEmpty())
        // And the behaviour: the planner treats it as constant speed.
        assertEquals(
            decoded.clips[0].effectiveDurationMs,
            SpeedRampPlanner.outputDurationMs(decoded.clips[0]),
        )
    }

    @Test
    fun `curves and speed ramps survive a JSON round trip`() {
        val curves = ColorCurves(
            master = Curve(listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.55f), CurvePoint(1f, 0.95f))),
            red = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))),
            green = Curve(listOf(CurvePoint(0f, 0.02f), CurvePoint(1f, 0.98f))),
            blue = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 0.9f))),
        )
        val source = clip(
            curves = curves,
            effects = listOf(ClipEffect(EffectKind.BLUR, 0.3f)),
        ).copy(
            speedRamps = listOf(
                SpeedPoint(0, 1f), SpeedPoint(4_000, 0.5f), SpeedPoint(10_000, 2f),
            ),
        )
        val json = projectJson.encodeToString(source)
        val decoded = projectJson.decodeFromString<Clip>(json)
        assertEquals(source, decoded)
        assertEquals(curves, decoded.curves)
        assertEquals(3, decoded.speedRamps.size)
        assertEquals(0.5f, decoded.speedRamps[1].factor, 1e-6f)
    }

    @Test
    fun `a full v4 project round trips through the document json`() {
        val decorated = clip("a", effects = listOf(ClipEffect(EffectKind.VIGNETTE, 0.6f)))
            .copy(
                curves = ColorCurves(
                    master = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))),
                    red = Curve(listOf(CurvePoint(0f, 0.1f), CurvePoint(1f, 0.9f))),
                    green = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))),
                    blue = Curve(listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))),
                ),
                speedRamps = listOf(SpeedPoint(0, 2f), SpeedPoint(10_000, 0.25f)),
            )
        val source = OvgProject(
            id = "p", name = "n", createdAtEpochMs = 1, updatedAtEpochMs = 2,
            // A v4-TAGGED document (schemaVersion is informational and
            // preserved as-is on a round trip — the v5 default applies to
            // NEW documents, not to re-encodes of old ones).
            schemaVersion = 4,
        ).copy(
            clips = listOf(decorated, clip("b")),
            transitions = listOf(
                ClipTransition(id = "t1", betweenClipIds = "a" to "b", kind = TransitionKind.CROSSFADE),
            ),
        )
        val json = projectJson.encodeToString(source)
        val decoded = projectJson.decodeFromString<OvgProject>(json)
        assertEquals(source, decoded)
        assertEquals(4, decoded.schemaVersion)
    }

    // ------------------------------------------------- v0.8.0 (schema v5, audio)

    @Test
    fun `a v4 document decodes to schema v5 audio defaults`() {
        // A v0.7.0 document: audioClips carry no fades/keyframes/gate. The
        // v5 defaults keep it loadable and byte-compatible — the v4 audio
        // behaviour exactly (constant volume, no automation).
        val v4Json = """
            {"schemaVersion":4,"id":"p","name":"n","createdAtEpochMs":1,"updatedAtEpochMs":2,
             "clips":[],"overlays":[],
             "audioClips":[{"id":"a1","uri":"u","displayName":"music","sourceDurationMs":30000,
                            "startMs":0,"trimStartMs":0,"trimEndMs":30000,"volume":0.8}],
             "transitions":[]}
        """.trimIndent()
        val decoded = projectJson.decodeFromString<OvgProject>(v4Json)
        assertEquals(4, decoded.schemaVersion)
        val audio = decoded.audioClips[0]
        assertEquals(0L, audio.fadeInMs)
        assertEquals(0L, audio.fadeOutMs)
        assertTrue(audio.gainKeyframes.isEmpty())
        assertFalse(audio.noiseGate)
        assertFalse(audio.hasGainAutomation)
        // And the behaviour: the gain math treats it as constant volume.
        assertEquals(
            audio.volume,
            com.onevideog.editor.editor.AudioGainMath.gainAt(audio, 12_345),
            1e-6f,
        )
    }

    @Test
    fun `audio gain automation survives a JSON round trip`() {
        val source = OvgProject(
            id = "p", name = "n", createdAtEpochMs = 1, updatedAtEpochMs = 2,
        ).copy(
            audioClips = listOf(
                AudioClipItem(
                    id = "a1", uri = "u", displayName = "narration",
                    sourceDurationMs = 30_000, startMs = 2_000,
                    trimStartMs = 500, trimEndMs = 25_000,
                    volume = 1.4f, fadeInMs = 1_500, fadeOutMs = 2_500,
                    gainKeyframes = listOf(
                        AudioKeyframe(0, 0.5f),
                        AudioKeyframe(4_000, 2f),
                        AudioKeyframe(9_000, 0.8f),
                    ),
                    noiseGate = true,
                ),
            ),
        )
        val json = projectJson.encodeToString(source)
        val decoded = projectJson.decodeFromString<OvgProject>(json)
        assertEquals(source, decoded)
        val audio = decoded.audioClips[0]
        assertEquals(1_500L, audio.fadeInMs)
        assertEquals(2_500L, audio.fadeOutMs)
        assertEquals(3, audio.gainKeyframes.size)
        assertEquals(2f, audio.gainKeyframes[1].volume, 1e-6f)
        assertTrue(audio.noiseGate)
        // v0.9.0: schema v6 (additive — styles/zDepth/camera); the v5 audio
        // fields round-trip unchanged.
        assertEquals(6, decoded.schemaVersion)
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
    fun `blur builds ONE effect with both separable passes and identity intensities build nothing`() {
        // v0.9.1 (BUG A): ONE GlEffect — the H/V passes live inside its
        // shader program (the chained pair is the chain shape that died on
        // the affected device after the v0.9.0 camera).
        val singleEffect = ClipGlEffects.effectsFor(
            listOf(ClipEffect(EffectKind.BLUR, 0.5f)),
        )
        assertEquals(1, singleEffect.size)

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
        // building. BLUR = ONE effect (v0.9.1), so a full 3-effect list
        // builds exactly 1 + 1 + 1 = 3 GL instances; a 4th BLUR would add 1.
        val overLimit = ClipGlEffects.effectsFor(
            listOf(
                ClipEffect(EffectKind.BLUR, 0.4f),
                ClipEffect(EffectKind.VIGNETTE, 0.4f),
                ClipEffect(EffectKind.SHARPEN, 0.4f),
                ClipEffect(EffectKind.BLUR, 0.6f),
            ),
        )
        assertEquals(3, overLimit.size)
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
