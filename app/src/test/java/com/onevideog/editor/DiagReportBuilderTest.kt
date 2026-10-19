package com.onevideog.editor

import com.onevideog.editor.diag.DiagReportBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clipboard report contract (spec section 6) — empty, normal, truncated. */
class DiagReportBuilderTest {

    private fun build(
        logLines: List<String> = listOf(
            "2026-10-19 18:00:00.000 | INFO | export | TRANSFORMER_START | op=exp-abc | Transformer started",
        ),
        ops: List<String> = listOf("op=exp-abc kind=export stage=DONE severity=INFO events=7 state=success"),
    ) = DiagReportBuilder.build(
        appVersion = "0.2.3 (5)",
        device = "realme RMX3624",
        androidVersion = "13",
        sdkInt = 33,
        enabled = true,
        eventCount = 42,
        approximateKb = 128,
        rotated = false,
        lastWriteFailed = false,
        opSummaries = ops,
        lastExportState = "op=exp-abc state=FAILED stage=TRANSFORMER_ERROR cat=ENCODING code=1",
        interruptedExport = null,
        logLines = logLines,
    )

    @Test
    fun `report contains identity state and evidence sections`() {
        val report = build()
        assertTrue(report.contains("app_version=0.2.3 (5)"))
        assertTrue(report.contains("device=realme RMX3624"))
        assertTrue(report.contains("android=13 (SDK 33)"))
        assertTrue(report.contains("diagnostics_enabled=true"))
        assertTrue(report.contains("events_recorded=42"))
        assertTrue(report.contains("op=exp-abc"))
        assertTrue(report.contains("Last known export state"))
        assertTrue(report.contains("cat=ENCODING"))
        assertTrue(report.contains("For the developer"))
    }

    @Test
    fun `empty log produces actionable guidance instead of failure`() {
        val report = build(logLines = emptyList(), ops = emptyList())
        assertTrue(report.contains("no events recorded"))
        assertTrue(report.contains("none recorded"))
    }

    @Test
    fun `rotation and write-failure warnings are surfaced`() {
        val rotated = DiagReportBuilder.build(
            appVersion = "0.2.3", device = "d", androidVersion = "13", sdkInt = 33,
            enabled = true, eventCount = 9999, approximateKb = 2048, rotated = true,
            lastWriteFailed = true, opSummaries = emptyList(), lastExportState = null,
            interruptedExport = null, logLines = listOf("x"),
        )
        assertTrue(rotated.contains("rotated"))
        assertTrue(rotated.contains("FAILED"))
    }

    @Test
    fun `interrupted export marker is reported`() {
        val report = DiagReportBuilder.build(
            appVersion = "0.2.3", device = "d", androidVersion = "13", sdkInt = 33,
            enabled = true, eventCount = 5, approximateKb = 4, rotated = false,
            lastWriteFailed = false, opSummaries = emptyList(), lastExportState = null,
            interruptedExport = "warning=previous export was INTERRUPTED (process death) — op=exp-xyz stage=RUNNING at=1",
            logLines = emptyList(),
        )
        assertTrue(report.contains("INTERRUPTED"))
        assertTrue(report.contains("op=exp-xyz"))
    }

    @Test
    fun `log tail is capped and marks the omission`() {
        val many = (1..500).map { "line $it" }
        val report = DiagReportBuilder.build(
            appVersion = "0.2.3", device = "d", androidVersion = "13", sdkInt = 33,
            enabled = true, eventCount = 500, approximateKb = 64, rotated = false,
            lastWriteFailed = false, opSummaries = emptyList(), lastExportState = null,
            interruptedExport = null, logLines = many,
        )
        assertTrue(report.contains("older lines omitted"))
        assertFalse(report.contains("line 1\n")) // head was cut (tail kept)
        assertTrue(report.contains("line 500"))
    }
}
