package io.github.khorkovenko.jenkinsbuilds

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBList
import io.github.khorkovenko.jenkinsbuilds.model.ParamDef
import io.github.khorkovenko.jenkinsbuilds.model.ParamKind
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsConfigurable
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsSettings
import io.github.khorkovenko.jenkinsbuilds.ui.BuildParametersDialog
import io.github.khorkovenko.jenkinsbuilds.ui.JenkinsPanel
import java.awt.Component
import java.awt.Container
import javax.swing.JCheckBox
import javax.swing.JTextField

/**
 * Builds the real UI classes inside a headless IDE, so a mistake that only shows when the tool window opens (such as
 * a field used before it is set) fails here. No server is configured, so nothing goes to the network.
 */
class JenkinsUiTest : BasePlatformTestCase() {

    private fun <T : Component> all(root: Component, type: Class<T>, into: MutableList<T> = ArrayList()): List<T> {
        if (type.isInstance(root)) into += type.cast(root)
        if (root is Container) root.components.forEach { all(it, type, into) }
        return into
    }

    private fun settings(): JenkinsSettings = JenkinsSettings.getInstance().also {
        // The headless IDE keeps its settings between tests
        it.loadState(JenkinsSettings.State())
    }

    private fun panel(anchor: ToolWindowAnchor): JenkinsPanel {
        val window = object : ToolWindowHeadlessManagerImpl.MockToolWindow(project) {
            override fun getAnchor(): ToolWindowAnchor = anchor
        }
        return JenkinsPanel(project, window).also { Disposer.register(testRootDisposable, it) }
    }

    private fun groups(tree: javax.swing.JTree): List<String> {
        val root = tree.model.root
        return (0 until tree.model.getChildCount(root)).map { tree.model.getChild(root, it).toString() }
    }

    fun testFavouriteJobsAreGroupedAndCanBeShownAlone() {
        val settings = settings()
        val panel = panel(ToolWindowAnchor.BOTTOM)
        val tree = all(panel, com.intellij.ui.treeStructure.Tree::class.java).single()
        val server = io.github.khorkovenko.jenkinsbuilds.api.JenkinsParser.parseServerTree(
            javaClass.getResourceAsStream("/fixtures/server.json")!!.use { String(it.readAllBytes(), Charsets.UTF_8) },
        )
        panel.showTree(server)
        assertEquals(listOf("all", "Portal", "Tools"), groups(tree))

        // A click on the star of the first job row marks it; a click on its name does not
        tree.setSize(900, 600)
        tree.expandRow(0)
        val bounds = tree.getRowBounds(1)
        fun click(x: Int) {
            val event = java.awt.event.MouseEvent(
                tree, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, x, bounds.y + bounds.height / 2, 1, false, java.awt.event.MouseEvent.BUTTON1,
            )
            tree.mouseListeners.forEach { it.mouseClicked(event) }
        }
        click(bounds.x + 30)
        assertTrue("a click on the name is not a click on the star", settings.favouriteJobs.isEmpty())
        var x = bounds.x
        while (settings.favouriteJobs.isEmpty() && x < bounds.x + bounds.width) {
            click(x)
            x += 3
        }
        assertEquals(listOf("customersportal-2.2-dev"), settings.favouriteJobs)
        assertEquals(listOf("Favourites", "all", "Portal", "Tools"), groups(tree))

        settings.setFavouriteJob("tools/deploy scripts/feature%2Fone", true)
        settings.onlyFavouriteJobs = true
        panel.showTree(server)
        assertEquals(listOf("Favourites"), groups(tree))
        assertEquals(2, tree.model.getChildCount(tree.model.getChild(tree.model.root, 0)))

        settings.onlyFavouriteJobs = false
        panel.showTree(server)
        assertEquals(4, groups(tree).size)
    }

    fun testToolWindowOpensSideBySideAtTheBottom() {
        settings()
        val panel = panel(ToolWindowAnchor.BOTTOM)
        val splitters = all(panel, OnePixelSplitter::class.java)
        assertEquals(2, splitters.size)
        assertTrue(splitters.none { it.orientation })
        assertNotNull(panel.toolbar)
    }

    fun testToolWindowOpensStackedAtTheSide() {
        val state = settings().state
        val panel = panel(ToolWindowAnchor.RIGHT)
        val splitters = all(panel, OnePixelSplitter::class.java)
        assertTrue(splitters.all { it.orientation })
        assertEquals(state.jobsSplitVertical, splitters[0].proportion, 0.001f)

        // Moving a divider is remembered for the stacked layout only
        splitters[0].proportion = 0.5f
        assertEquals(0.5f, state.jobsSplitVertical, 0.001f)
        assertEquals(0.24f, state.jobsSplitHorizontal, 0.001f)
    }

    fun testLayoutFollowsTheShapeOfTheWindow() {
        settings()
        val panel = panel(ToolWindowAnchor.BOTTOM)
        val content = panel.content!!
        val splitters = all(panel, OnePixelSplitter::class.java)
        content.setSize(400, 900)
        content.componentListeners.forEach { it.componentResized(java.awt.event.ComponentEvent(content, java.awt.event.ComponentEvent.COMPONENT_RESIZED)) }
        assertTrue(splitters.all { it.orientation })
        content.setSize(1400, 300)
        content.componentListeners.forEach { it.componentResized(java.awt.event.ComponentEvent(content, java.awt.event.ComponentEvent.COMPONENT_RESIZED)) }
        assertTrue(splitters.none { it.orientation })
    }

    fun testSettingsPageShowsTheSavedValues() {
        val state = settings().state
        state.url = "https://jenkins.example.test"
        state.username = "tester"
        val page = JenkinsConfigurable()
        try {
            val component = page.createComponent()
            page.reset()
            val texts = all(component, JTextField::class.java).map { it.text }
            assertTrue(texts.contains("https://jenkins.example.test"))
            assertTrue(texts.contains("tester"))
            assertFalse(page.isModified)
        } finally {
            page.disposeUIResources()
        }
    }

    fun testBuildFormReturnsTheValuesAndKeepsFavourites() {
        val settings = settings()
        settings.setFavouriteValue("origin/cp/2.2/dev", true)
        val definitions = listOf(
            ParamDef("Branch_Selector", ParamKind.LIST, ".*", "origin/master", listOf("origin/master", "origin/cp/2.1/CP-356"), null),
            ParamDef("PROFILE", ParamKind.CHOICE, null, "stage", listOf("dev", "stage", "prod"), null),
            ParamDef("SKIP_TESTS", ParamKind.BOOLEAN, null, "true", emptyList(), null),
            ParamDef("NOTES", ParamKind.TEXT, null, "a\nb", emptyList(), null),
            ParamDef("VERSION", ParamKind.STRING, null, null, emptyList(), null),
            ParamDef("KEY", ParamKind.PASSWORD, null, null, emptyList(), null),
        )
        val dialog = BuildParametersDialog(project, "x", "Build x", definitions, mapOf("VERSION" to "1.2"))
        try {
            assertEquals(
                listOf("Branch_Selector" to "origin/master", "PROFILE" to "stage", "SKIP_TESTS" to "true", "NOTES" to "a\nb", "VERSION" to "1.2"),
                dialog.values(),
            )
            assertTrue("a description without words is not shown", all(dialog.formPanel!!, javax.swing.JLabel::class.java).none { it.text?.contains(".*") == true })
            // The favourite branch is offered first although the server did not list it
            val list = all(dialog.formPanel!!, JBList::class.java).first()
            assertEquals("origin/cp/2.2/dev", list.model.getElementAt(0))
            assertEquals(3, list.model.size)

            val only = all(dialog.formPanel!!, JCheckBox::class.java).first { it.text == "Only favourites" }
            only.doClick()
            assertEquals(1, list.model.size)
            assertTrue(settings.onlyFavouriteValues)
        } finally {
            dialog.close(0)
        }
    }
}
