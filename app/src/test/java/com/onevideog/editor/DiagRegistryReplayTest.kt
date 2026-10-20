package com.onevideog.editor

import com.onevideog.editor.diag.DiagRegistryReplay
import com.onevideog.editor.diag.OpRegistry
import com.onevideog.editor.diag.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.2.5 regression: op summaries must survive a process restart. The
 * registry is in-memory by design, so the report now replays the persisted
 * log tail into it. These tests pin the replay parser contract.
 */
class DiagRegistryReplayTest {

    @Test
    fun `replay rebuilds op counts kinds and final states`() {
        val registry = OpRegistry()
        DiagRegistryReplay.replay(
            listOf(
                "2026-10-20 09:00:00.000 | INFO | export | REQUEST | op=exp-aaaa | k=v | starting",
                "2026-10-20 09:00:01.000 | INFO | export | TRANSFORMER_START | op=exp-aaaa | ok",
                "2026-10-20 09:00:02.000 | INFO | export | DONE | op=exp-aaaa | finished",
                "2026-10-20 09:01:00.000 | ERROR | import | DECODE_FAIL | op=img-bbbb | exc=java.io.IOException",
            ),
            registry,
        )
        val export = registry.find("exp-aaaa")!!
        assertEquals(3, export.events)
        assertEquals(OpRegistry.KIND_EXPORT, export.kind)
        assertEquals("DONE", export.lastStage)
        assertEquals("success", export.finalState)
        val img = registry.find("img-bbbb")!!
        assertEquals(1, img.events)
        assertEquals(OpRegistry.KIND_IMAGE_IMPORT, img.kind)
        assertEquals("failure", img.finalState)
    }

    @Test
    fun `malformed and op-less lines are skipped, never thrown`() {
        val registry = OpRegistry()
        DiagRegistryReplay.replay(
            listOf(
                "garbage",
                "",
                "2026-10-20 09:00:00.000 | INFO | only-three-fields",
                "2026-10-20 09:00:00.000 | NOT_A_SEVERITY | export | DONE | op=exp-x",
                "2026-10-20 09:00:00.000 | INFO | export | DONE", // no 5th field
                "2026-10-20 09:00:00.000 | INFO | export | DONE | notanop=1",
            ),
            registry,
        )
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test
    fun `session events merge with replayed ones by op id`() {
        val registry = OpRegistry()
        // Replay a persisted tail...
        DiagRegistryReplay.replay(
            listOf("2026-10-20 08:00:00.000 | INFO | export | REQUEST | op=exp-old"),
            registry,
        )
        // ...then the live session records the SAME op id again.
        registry.record("exp-old", OpRegistry.KIND_EXPORT, "PROGRESS", Severity.INFO, 1_000)
        assertEquals(2, registry.find("exp-old")?.events)
        assertEquals("PROGRESS", registry.find("exp-old")?.lastStage)
    }

    @Test
    fun `timestamp parse falls back to 0 for unparsable stamps`() {
        assertEquals(0L, DiagRegistryReplay.parseTimestamp("not-a-date"))
        assertTrue(DiagRegistryReplay.parseTimestamp("2026-10-20 09:00:00.000") > 0)
    }

    @Test
    fun `parse rejects lines whose 5th field is not the op`() {
        // Field order contract: op= must be the 5th field (right after stage).
        assertNull(
            DiagRegistryReplay.parse(
                "2026-10-20 09:00:00.000 | INFO | export | DONE | k=v | op=exp-late",
            ),
        )
    }
}
