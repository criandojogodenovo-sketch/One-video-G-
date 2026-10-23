package com.onevideog.editor.editor

import com.onevideog.editor.data.TransitionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.6.0 (Fase H) — the transition shader contract on the CPU reference
 * ([TransitionShaders.applyToFrame] is the behavioural spec both pipelines
 * render through the identical GLSL):
 *  - the OUT (tail) phase STARTS at the identity and ends fully
 *    black/shifted-out; the IN (head) phase STARTS black/off-frame (visual
 *    continuity with the black tail end) and ENDS back at the identity —
 *    so frames OUTSIDE each window are never disturbed;
 *  - CROSSFADE dims linearly; WIPE/SLIDE shift the content and fill with
 *    black;
 *  - the GLSL follows the Media3 1.4.1 sampling conventions.
 */
class TransitionShadersTest {

    private val w = 8
    private val h = 8

    /** Gradient-ish frame: pixel value depends on x. */
    private fun frame(): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val v = 40 + x * 20
        0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
    }

    private fun isBlack(pixel: Int): Boolean = (pixel and 0xFFFFFF) == 0

    @Test
    fun `window boundaries are the identity - out starts clean, in ends clean`() {
        for (kind in TransitionKind.entries) {
            // OUT window start (p=0): the frame is untouched.
            val f = frame()
            val outStart = TransitionShaders.applyToFrame(f.copyOf(), w, h, kind, 0, 0f)
            assertEquals("kind=$kind phase=out p=0", f.toList(), outStart.toList())
            // IN window end (p=1): the fade-in completed, the frame is
            // back to the identity.
            val inEnd = TransitionShaders.applyToFrame(f.copyOf(), w, h, kind, 1, 1f)
            assertEquals("kind=$kind phase=in p=1", f.toList(), inEnd.toList())
        }
    }

    @Test
    fun `crossfade tail dims to black and head fades from black`() {
        val f = frame()
        val half = TransitionShaders.applyToFrame(f.copyOf(), w, h, TransitionKind.CROSSFADE, 0, 0.5f)
        // Tail at p=0.5: brightness halved.
        val srcV = (f[0] and 0xFF)
        val halfV = (half[0] and 0xFF)
        assertEquals(srcV * 0.5f, halfV.toFloat(), 1.5f)
        val end = TransitionShaders.applyToFrame(f.copyOf(), w, h, TransitionKind.CROSSFADE, 0, 1f)
        assertTrue(end.all { isBlack(it) })
        // Head at p=1 is back to the identity (the fade-in completed).
        val headEnd = TransitionShaders.applyToFrame(f.copyOf(), w, h, TransitionKind.CROSSFADE, 1, 1f)
        assertEquals(f.toList(), headEnd.toList())
        // Head at p=0.5 is also half-bright.
        val headHalf = TransitionShaders.applyToFrame(f.copyOf(), w, h, TransitionKind.CROSSFADE, 1, 0.5f)
        assertEquals(srcV * 0.5f, (headHalf[0] and 0xFF).toFloat(), 1.5f)
    }

    @Test
    fun `directional tail pushes the content out and ends black`() {
        val f = frame()
        val out = TransitionShaders.applyToFrame(f.copyOf(), w, h, TransitionKind.WIPE_RIGHT, 0, 1f)
        assertTrue("WIPE_RIGHT tail at p=1 must be fully pushed out", out.all { isBlack(it) })
        // At p=0.5 the content moved RIGHT by half the width: the left half
        // of the frame is black, the right half shows the left half content.
        val mid = TransitionShaders.applyToFrame(f.copyOf(), w, h, TransitionKind.WIPE_RIGHT, 0, 0.5f)
        assertTrue(isBlack(mid[0])) // x=0 -> source x=-4 -> black
        assertEquals(f[(h / 2) * w + 0], mid[(h / 2) * w + (w / 2)]) // x=4 shows source x=0
    }

    @Test
    fun `directional head enters from the opposite side and ends at the identity`() {
        val f = frame()
        val start = TransitionShaders.applyToFrame(f.copyOf(), w, h, TransitionKind.SLIDE_UP, 1, 0f)
        // At p=0 the IN content is fully off-frame (entered nothing yet).
        assertTrue(start.all { isBlack(it) })
        // At p=0.5 the content is shifted DOWN (coming from below, moving up
        // in screen space): top half black, bottom half shows the top content.
        val mid = TransitionShaders.applyToFrame(f.copyOf(), w, h, TransitionKind.SLIDE_UP, 1, 0.5f)
        assertTrue(isBlack(mid[0])) // y=0 -> source y=-4 -> black
        assertEquals(f[0], mid[(h / 2) * w])
        val end = TransitionShaders.applyToFrame(f.copyOf(), w, h, TransitionKind.SLIDE_UP, 1, 1f)
        assertEquals(f.toList(), end.toList())
    }

    @Test
    fun `screen direction mapping matches the documented spec`() {
        assertEquals(-1f to 0f, TransitionShaders.dirScreen(TransitionKind.WIPE_LEFT))
        assertEquals(1f to 0f, TransitionShaders.dirScreen(TransitionKind.WIPE_RIGHT))
        assertEquals(0f to -1f, TransitionShaders.dirScreen(TransitionKind.SLIDE_UP))
        assertEquals(0f to 1f, TransitionShaders.dirScreen(TransitionKind.SLIDE_DOWN))
        // The texture-space direction flips y (GL v axis grows upward).
        assertEquals(0f to 1f, TransitionShaders.dirTex(TransitionKind.SLIDE_UP))
        assertEquals(0f to -1f, TransitionShaders.dirTex(TransitionKind.SLIDE_DOWN))
        assertEquals(1f to 0f, TransitionShaders.dirTex(TransitionKind.WIPE_RIGHT))
    }

    @Test
    fun `glsl follows the media3 sampling conventions`() {
        val code = TransitionShaders.fragmentShaderCode()
        assertTrue(code.startsWith("#version 100"))
        assertTrue(code.contains("uniform sampler2D uTexSampler"))
        assertTrue(code.contains("varying vec2 vTexSamplingCoord"))
        assertTrue(code.contains(TransitionShaders.UNIFORM_PROGRESS))
        assertTrue(code.contains(TransitionShaders.UNIFORM_PHASE))
        assertTrue(code.contains(TransitionShaders.UNIFORM_MODE))
        assertTrue(code.contains(TransitionShaders.UNIFORM_DIR))
        assertTrue(code.contains("void main()"))
        assertTrue(code.contains("gl_FragColor"))
    }

    @Test
    fun `progress is clamped - late frames on the out phase stay at the window end`() {
        val f = frame()
        val clamped = TransitionShaders.applyToFrame(f.copyOf(), w, h, TransitionKind.CROSSFADE, 0, 3f)
        val atOne = TransitionShaders.applyToFrame(f.copyOf(), w, h, TransitionKind.CROSSFADE, 0, 1f)
        assertEquals(atOne.toList(), clamped.toList())
    }

    @Test
    fun `out of range progress below zero clamps to identity`() {
        val f = frame()
        val clamped = TransitionShaders.applyToFrame(f.copyOf(), w, h, TransitionKind.CROSSFADE, 0, -2f)
        assertEquals(f.toList(), clamped.toList())
    }
}
