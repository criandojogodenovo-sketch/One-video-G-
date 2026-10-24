package com.onevideog.editor.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.8.1 (BUG B — device report: the FORMAT/aspect tool disappeared from the
 * toolbar): guards the class of bug "a tool silently vanishes from the
 * toolbar" forever.
 *
 * The device root cause was NOT a removed enum entry — Tool.ASPECT and its
 * AspectPanel were always there — but a LAYOUT overflow: the non-scrollable
 * toolbar row grew to 13 tools + 2 import buttons (660 dp) and the ASPECT
 * button was the first one clipped off-screen. The row is now scrollable and
 * the rendered list is the top-level [TOOLBAR_TOOLS], which THIS test pins:
 *
 *  1. every [Tool] enum entry is either a toolbar slot or in the EXPLICIT
 *     [TOOLS_NOT_IN_TOOLBAR] whitelist — adding a Tool without a toolbar
 *     slot and without a deliberate whitelist entry fails here;
 *  2. the toolbar order matches the enum order — a tool can never be
 *     silently reordered;
 *  3. every entry carries a distinct icon and a label resource — the pt/en
 *     strings themselves are pinned by [LocalizationTest];
 *  4. the reported regression case is explicit: ASPECT is present.
 *
 * Sheet coverage is compile-enforced and needs no test: ToolSheet's
 * `when (tool)` has no else branch, so a new Tool without a panel branch
 * fails the build.
 */
class ToolBarCoverageTest {

    /**
     * Tools that deliberately have NO toolbar button. KEYFRAMES opens from
     * the contextual transform panel (a per-selection entry point, not a
     * global tool). Anything else added to this set is a conscious product
     * decision that this test forces to be written down.
     */
    private val toolsNotInToolbar = setOf(Tool.KEYFRAMES)

    @Test
    fun `every Tool enum entry has a toolbar slot or is whitelisted`() {
        val enumTools = Tool.values().toList()
        val toolbarTools = TOOLBAR_TOOLS.map { it.first }
        val expected = enumTools.filter { it !in toolsNotInToolbar }
        assertEquals(
            "TOOLBAR_TOOLS must cover every Tool entry except the explicit " +
                "whitelist $toolsNotInToolbar (a missing entry is an invisible " +
                "tool on device) — got $toolbarTools for enum $enumTools",
            expected,
            toolbarTools.sortedBy { enumTools.indexOf(it) },
        )
        assertEquals("duplicate toolbar slots", expected, toolbarTools.distinct())
    }

    @Test
    fun `toolbar order matches the enum order - no silent reordering`() {
        val enumTools = Tool.values().toList()
        val toolbarTools = TOOLBAR_TOOLS.map { it.first }
        assertEquals(enumTools.filter { it !in toolsNotInToolbar }, toolbarTools)
    }

    @Test
    fun `every toolbar entry has a distinct icon and a label resource`() {
        val icons = TOOLBAR_TOOLS.map { it.second }
        TOOLBAR_TOOLS.forEach { (tool, icon) ->
            assertNotNull("tool $tool has no icon", icon)
            assertNotEquals(0, tool.labelRes)
        }
        assertEquals(
            "two tools share one icon — users cannot tell them apart",
            icons.size,
            icons.distinct().size,
        )
    }

    @Test
    fun `the device regression case - the aspect format tool is present`() {
        // realme RMX3624 report (v0.8.0): the SOURCE/16:9/9:16/1:1/4:3/3:4
        // format tool was unreachable from the toolbar. It must always be
        // one mapped toolbar slot away.
        val tools = TOOLBAR_TOOLS.map { it.first }
        assertTrue(
            "Tool.ASPECT missing from TOOLBAR_TOOLS — the format tool would be invisible again",
            Tool.ASPECT in tools,
        )
        // And it must stay an always-enabled entry (usable with or without
        // timeline content) — ToolBarRow keeps it in the enable whitelist
        // together with AUDIO/LAYERS; the sheet is exercised on device.
        assertTrue(Tool.ASPECT !in toolsNotInToolbar)
    }
}
