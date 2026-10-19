package com.onevideog.editor

import com.onevideog.editor.diag.ExportOp
import com.onevideog.editor.diag.ImageImportStages
import com.onevideog.editor.diag.OpRegistry
import com.onevideog.editor.diag.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correlation + lifecycle-transition contract (spec sections 3/4: every
 * import/export connects through an operation id; stage attribution).
 */
class DiagOpsTest {

    @Test
    fun `op ids are prefixed and distinct`() {
        val a = OpRegistry.newOpId("img")
        val b = OpRegistry.newOpId("img", seed = 42L)
        assertTrue(a.startsWith("img-") && a.length == 12)
        assertTrue(b.startsWith("img-"))
        assertFalse(a == b)
    }

    @Test
    fun `registry aggregates events per op and evicts oldest beyond capacity`() {
        val registry = OpRegistry(capacity = 3)
        registry.record("op1", "export", "REQUEST", Severity.INFO, 1)
        registry.record("op1", "export", "PLAN", Severity.INFO, 2)
        registry.record("op2", "export", "REQUEST", Severity.INFO, 3)
        registry.record("op3", "image-import", "DECODE_START", Severity.INFO, 4)
        registry.record("op4", "image-import", "DECODE_END", Severity.INFO, 5)

        assertEquals(3, registry.snapshot().size)
        assertNull(registry.find("op1")) // oldest evicted
        assertEquals(1, registry.find("op4")!!.events)
        // newest first
        assertEquals("op4", registry.snapshot().first().opId)
    }

    @Test
    fun `export op walks lifecycle stages in order`() {
        val registry = OpRegistry()
        val op = ExportOp("exp-1", registry) { 100L }
        assertEquals(ExportOp.Stages.REQUEST, op.stage)

        op.transition(ExportOp.Stages.VALIDATE)
        op.transition(ExportOp.Stages.PREFLIGHT)
        op.transition(ExportOp.Stages.TRANSFORMER_START)
        assertTrue(op.reachedTransformer)
        op.transition(ExportOp.Stages.PROGRESS)
        op.transition(ExportOp.Stages.COMPLETED)
        op.transition(ExportOp.Stages.FINALIZE)
        op.transition(ExportOp.Stages.DONE)

        assertEquals(ExportOp.Stages.DONE, op.stage)
        assertNull(op.failureCategory)
        val record = registry.find("exp-1")!!
        assertEquals("success", record.finalState)
        assertEquals(7, record.events)
    }

    @Test
    fun `export failure records category and final state`() {
        val registry = OpRegistry()
        val op = ExportOp("exp-2", registry)
        op.transition(ExportOp.Stages.TRANSFORMER_START)
        op.fail("ENCODING", "code=1")
        assertEquals(ExportOp.Stages.FAILED, op.stage)
        assertEquals("ENCODING", op.failureCategory)
        assertEquals("failure", registry.find("exp-2")!!.finalState)
        val summary = op.summaryLine()
        assertTrue(summary.contains("op=exp-2"))
        assertTrue(summary.contains("FAILED/ENCODING"))
    }

    @Test
    fun `failure before transformer start is attributable`() {
        val registry = OpRegistry()
        val op = ExportOp("exp-3", registry)
        op.transition(ExportOp.Stages.PREFLIGHT)
        op.fail("INSUFFICIENT_STORAGE", "needed=1 free=0")
        assertFalse(op.reachedTransformer)
        // failure attribution: the registry keeps WHERE it failed
        assertTrue(registry.find("exp-3")!!.lastStage.contains("PREFLIGHT"))
        assertEquals("INSUFFICIENT_STORAGE", op.failureCategory)
    }

    @Test
    fun `image import stage constants cover the spec alphabet`() {
        val stages = listOf(
            ImageImportStages.PICKER_RESULT,
            ImageImportStages.URI_META,
            ImageImportStages.PERMISSION,
            ImageImportStages.ACCESS,
            ImageImportStages.BOUNDS,
            ImageImportStages.DECODE_START,
            ImageImportStages.DECODE_END,
            ImageImportStages.DECODE_FAIL,
            ImageImportStages.OVERLAY_CREATE,
            ImageImportStages.PREVIEW_REBUILD,
            ImageImportStages.EFFECT_ATTACH,
            ImageImportStages.ACTIVATION,
            ImageImportStages.RENDER_FAIL,
        )
        assertEquals(stages.size, stages.toSet().size)
        assertTrue(stages.containsAll(listOf("PICKER_RESULT", "DECODE_FAIL", "RENDER_FAIL")))
    }
}
