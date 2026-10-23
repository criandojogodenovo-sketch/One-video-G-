package com.onevideog.editor.editor

import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.SpeedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.7.0 (K3) — the video-sequence plan contract, locked on the JVM:
 *  - clips WITHOUT speed ramps keep the v0.6.0 one-item-per-clip path
 *    (ramped = false, source window = the clip trim, speed = clip.speed);
 *  - ramped clips split into MULTIPLE items, each with its segment speed and
 *    its source window (trimStart/trimEnd of the segment, K3), contiguous in
 *    source and output;
 *  - the plan's total output duration equals ProjectMath.videoDurationMs
 *    (the single-duration rule) and consecutive clips' output positions do
 *    not overlap.
 *
 * The pure plan is what CompositionFactory.build consumes to create the
 * EditedMediaItems — testing it on the JVM pins the export sequence without
 * needing an Android context.
 */
class CompositionFactoryTest {

    private fun clip(
        id: String,
        durMs: Long,
        speed: Float = 1f,
        ramps: List<SpeedPoint> = emptyList(),
    ) = Clip(
        id = id, uri = "u", displayName = "d", sourceDurationMs = durMs,
        trimStartMs = 0, trimEndMs = durMs, speed = speed, speedRamps = ramps,
    )

    private fun project(vararg clips: Clip) =
        OvgProject(id = "p", name = "n", createdAtEpochMs = 0, updatedAtEpochMs = 0)
            .copy(clips = clips.toList())

    @Test
    fun `clips without ramps produce one item each - v0_6_0 path`() {
        val p = project(clip("a", 5_000), clip("b", 3_000))
        val items = CompositionFactory.planVideoSequence(p)
        assertEquals(2, items.size)
        assertFalse(items[0].ramped)
        assertFalse(items[1].ramped)
        assertEquals(1f, items[0].speed, 1e-6f)
        assertEquals(0L, items[0].outputStartMs)
        assertEquals(5_000L, items[1].outputStartMs)
        assertEquals(5_000L, items[1].clipStartMs)
        // v0.6.0 source windows = the clip trims.
        assertEquals(0L, items[1].sourceStartMs)
        assertEquals(3_000L, items[1].sourceEndMs)
    }

    @Test
    fun `ramped clip produces multiple items with correct speeds`() {
        val ramped = clip(
            "r", 4_000,
            ramps = listOf(SpeedPoint(0, 0.5f), SpeedPoint(2_000, 2f), SpeedPoint(4_000, 0.5f)),
        )
        val p = project(ramped)
        val items = CompositionFactory.planVideoSequence(p)
        assertTrue("expected >= 3 items, got ${items.size}", items.size >= 3)
        assertTrue(items.all { it.ramped })
        // Slow start, fast middle, slow end (segment speeds are the factor at
        // the sub-segment MIDPOINT — the first segment's factor is already on
        // the 0.5->2.0 slope, so only range assertions are honest).
        assertTrue("first speed ${items.first().speed}", items.first().speed < 1f)
        assertTrue(items[items.size / 2].speed > 1f)
        assertTrue("last speed ${items.last().speed}", items.last().speed < 1.5f)
        // Source windows are contiguous and cover the clip trim.
        assertEquals(0L, items.first().sourceStartMs)
        assertEquals(4_000L, items.last().sourceEndMs)
        for (i in 1 until items.size) {
            assertEquals(items[i - 1].sourceEndMs, items[i].sourceStartMs)
        }
        // Output positions are contiguous.
        for (i in 1 until items.size) {
            assertEquals(
                items[i - 1].outputStartMs + items[i - 1].outputDurationMs,
                items[i].outputStartMs,
            )
        }
    }

    @Test
    fun `ramp changes the clip footprint and shifts later clips`() {
        // 4 s source; first half at 0.5x (-> 4 s output), second at 2x (-> 0.5 s).
        val ramped = clip(
            "r", 4_000,
            ramps = listOf(SpeedPoint(0, 0.5f), SpeedPoint(2_000, 0.5f), SpeedPoint(4_000, 2f)),
        )
        val p = project(ramped, clip("b", 1_000))
        val items = CompositionFactory.planVideoSequence(p)
        val b = items.last()
        assertEquals("b", b.clipId)
        // ramped clip output ~ 4 + 0.5 = 4.5 s (quantized); clip b starts there.
        val rampedOut = items.filter { it.clipId == "r" }.sumOf { it.outputDurationMs }
        assertEquals(rampedOut, b.outputStartMs)
        // Single-duration rule: plan total == ProjectMath.videoDurationMs.
        assertEquals(
            rampedOut + 1_000L,
            ProjectMath.videoDurationMs(p.clips),
        )
    }

    @Test
    fun `plan never exceeds the segment limit per clip`() {
        val ramps = (0 until 20).map { SpeedPoint(it * 500L, if (it % 2 == 0) 2f else 0.5f) }
        val p = project(clip("r", 10_000, ramps = ramps))
        val items = CompositionFactory.planVideoSequence(p)
        assertTrue(items.size <= SpeedRampPlanner.MAX_SEGMENTS)
        assertTrue(items.all { it.ramped })
    }

    @Test
    fun `constant ramp equals the plain constant speed clip footprint`() {
        // A constant-factor ramp (0, 1.0) -> (D, 1.0) at clip.speed 1 must
        // produce the same footprint as no ramp at all.
        val plain = clip("a", 5_000)
        val ramped = clip("b", 5_000, ramps = listOf(SpeedPoint(0, 1f), SpeedPoint(5_000, 1f)))
        val p = project(plain, ramped)
        val items = CompositionFactory.planVideoSequence(p)
        // The "b" clip: a constant ramp is STILL ramped for the export (its
        // segments carry SpeedChangeEffect(1.0) explicitly) but the total
        // footprint matches the plain clip.
        assertEquals(
            items.filter { it.clipId == "a" }.sumOf { it.outputDurationMs },
            items.filter { it.clipId == "b" }.sumOf { it.outputDurationMs },
        )
    }

    @Test
    fun `segment items keep their clip identity and start`() {
        val ramped = clip("r", 2_000, ramps = listOf(SpeedPoint(0, 1f), SpeedPoint(1_000, 0.5f), SpeedPoint(2_000, 1f)))
        val p = project(ramped)
        val items = CompositionFactory.planVideoSequence(p)
        assertTrue(items.size >= 2)
        items.forEach {
            assertEquals("r", it.clipId)
            assertEquals(0L, it.clipStartMs)
        }
        assertEquals(items.size, items.first().segmentCount) // all same clip
    }
}
