package io.github.khorkovenko.jenkinsbuilds.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.ui.PopupHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.BrowserHyperlinkListener
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsClient
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsParser
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsUrls
import io.github.khorkovenko.jenkinsbuilds.model.Artifact
import io.github.khorkovenko.jenkinsbuilds.model.BuildDetails
import io.github.khorkovenko.jenkinsbuilds.model.BuildRef
import io.github.khorkovenko.jenkinsbuilds.model.JobDetails
import io.github.khorkovenko.jenkinsbuilds.model.JobNode
import io.github.khorkovenko.jenkinsbuilds.model.ParamDef
import io.github.khorkovenko.jenkinsbuilds.model.ParamKind
import io.github.khorkovenko.jenkinsbuilds.model.QueueItem
import io.github.khorkovenko.jenkinsbuilds.model.ServerTree
import io.github.khorkovenko.jenkinsbuilds.model.ViewNode
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsConfigurable
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsSettings
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsSettingsListener
import com.intellij.openapi.ui.Splitter
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import com.intellij.ui.SimpleColoredComponent
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.openapi.util.IconLoader
import java.beans.PropertyChangeListener
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The tool window: jobs on the left, the builds of the selected job in the middle, the selected build on the right.
 *
 * All fields are used on the UI thread only. Network calls run on pooled threads through [load]; each kind of call has
 * a slot, and when a newer call of the same slot was started the answer of the older one is dropped.
 */
class JenkinsPanel(private val project: Project, private val toolWindow: ToolWindow) :
    SimpleToolWindowPanel(true, true), Disposable {

    // ---- rows of the tree and the table -------------------------------------------------------------------------------

    /** A view, a section of a view, or the "all jobs" group. */
    private class Group(val name: String, val view: Boolean) {
        override fun toString(): String = name
    }

    private sealed class Row {
        class Queued(val id: Long, val why: String?) : Row()
        class Build(val ref: BuildRef) : Row()
    }

    private class Tracked(val job: String, var why: String?)

    private class Watched(val job: String, val number: Int) {
        var failures = 0
    }

    enum class Badge { NONE, RUNNING, FINISHED, FAILED }

    private class Slot {
        var generation = 0
        var future: Future<*>? = null
    }

    // ---- state --------------------------------------------------------------------------------------------------------

    private var disposed = false
    private val slots = HashMap<String, Slot>()
    private var actionCounter = 0

    private var server: ServerTree? = null
    private var currentJob: JobNode? = null
    private var jobDetails: JobDetails? = null
    private var currentBuild: Int? = null
    private var buildDetails: BuildDetails? = null

    /** Queue items this window started and still watches, by id. */
    private val tracked = LinkedHashMap<Long, Tracked>()

    /** Running builds that are mine (started here, or by my user), by [watchKey]. */
    private val watched = LinkedHashMap<String, Watched>()

    /** Results of my builds that finished while the tool window was hidden. */
    private val unseen = ArrayList<String>()
    private val baseIcon: Icon = IconLoader.getIcon("/icons/jenkinsBuilds.svg", JenkinsPanel::class.java)
    private var shownBadge: java.awt.Color? = null

    /** A build to select as soon as it shows up in the list (a queue item that started). */
    private var selectWhenListed: Int? = null

    private var lastPoll = 0L
    private var consoleSession = 0
    private var consoleShown: Pair<String, Int>? = null

    /** The end of the log that has no line break yet. */
    private var consolePending = ""
    private val searchField = SearchTextField(false)
    private val searchLabel = JBLabel()
    private val searchMarks = ArrayList<RangeHighlighter>()
    private val artifactSizes = HashMap<String, Long>()

    private val expanded = HashSet<String>()
    private var firstTree = true
    private var rebuildingTree = false
    private var updatingTable = false

    // ---- components ---------------------------------------------------------------------------------------------------

    private val filterField = SearchTextField(false)
    private val treeRoot = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val jobTree = Tree(treeModel)

    private val rows = ArrayList<Row>()
    private val buildsModel = BuildsModel()
    private val buildsTable = JBTable(buildsModel)

    private val summaryPane = JEditorPane()
    private val artifacts = ArrayList<Artifact>()
    private val artifactsModel = ArtifactsModel()
    private val artifactsTable = JBTable(artifactsModel)
    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private val tabs = JBTabbedPane()
    private val statusLabel = JBLabel(" ")
    private val jobsSplit = OnePixelSplitter(false, 0.24f)
    private val buildsSplit = OnePixelSplitter(false, 0.42f)

    /** True when the parts are stacked; null until the first layout. */
    private var verticalLayout: Boolean? = null
    private var applyingLayout = false

    private val poller: ScheduledFuture<*>

    init {
        Disposer.register(this, console)
        buildUi()

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(JenkinsSettingsListener.TOPIC, JenkinsSettingsListener {
            ApplicationManager.getApplication().invokeLater({ if (!disposed) refreshAll() }, ModalityState.any())
        })
        poller = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            ApplicationManager.getApplication().invokeLater({ if (!disposed) tick() }, ModalityState.any())
        }, 2, 1, TimeUnit.SECONDS)

        refreshAll()
    }

    override fun dispose() {
        disposed = true
        poller.cancel(false)
        slots.values.forEach { it.future?.cancel(false) }
    }

    // ---- layout -------------------------------------------------------------------------------------------------------

    private fun buildUi() {
        val favouriteAction = createFavouriteAction()
        val group = DefaultActionGroup().apply {
            add(action("Refresh", "Load the jobs and the selected job again", AllIcons.Actions.Refresh, { true }) { refreshAll() })
            add(Separator.getInstance())
            add(action("Build", "Start a build of the selected job", AllIcons.Actions.Execute, { canBuild() }) { build(null) })
            add(action("Rebuild", "Start a build with the parameters of the selected build", AllIcons.Actions.Rerun, { canBuild() && buildDetails != null }) { rebuild() })
            add(action("Stop", "Stop the selected running build or cancel the selected queued one", AllIcons.Actions.Suspend, { canStop() }) { stop() })
            add(Separator.getInstance())
            add(action("Open in Browser", "Open the selected build or job in the browser", AllIcons.General.Web, { selectedUrl() != null }) {
                selectedUrl()?.let { BrowserUtil.browse(it) }
            })
            add(action("Copy URL", "Copy the address of the selected build or job", AllIcons.Actions.Copy, { selectedUrl() != null }) {
                selectedUrl()?.let { copy(it) }
            })
            add(Separator.getInstance())
            add(toggle("Show Favourites Only", "Show only the jobs marked with a star", AllIcons.Nodes.Favorite, { true },
                { JenkinsSettings.getInstance().onlyFavouriteJobs }) {
                JenkinsSettings.getInstance().onlyFavouriteJobs = it
                rebuildTree()
                status(if (it) "Showing the favourite jobs only" else "Showing all jobs")
            })
            add(action("Expand All", "Open every view and folder", AllIcons.Actions.Expandall, { treeRoot.childCount > 0 }) {
                var row = 0
                while (row < jobTree.rowCount) jobTree.expandRow(row++)
            })
            add(action("Collapse All", "Close every view and folder", AllIcons.Actions.Collapseall, { treeRoot.childCount > 0 }) {
                for (row in jobTree.rowCount - 1 downTo 0) jobTree.collapseRow(row)
            })
            add(Separator.getInstance())
            add(action("Jenkins Settings", "Server address and login", AllIcons.General.Settings, { true }) { openSettings() })
        }
        PopupHandler.installPopupMenu(jobTree, DefaultActionGroup(favouriteAction), "JenkinsBuilds.JobPopup")
        val toolbar = ActionManager.getInstance().createActionToolbar("JenkinsBuilds.Toolbar", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        // Jobs
        jobTree.isRootVisible = false
        jobTree.showsRootHandles = true
        jobTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        jobTree.cellRenderer = JobRenderer()
        jobTree.emptyText.text = "Loading..."
        jobTree.addTreeSelectionListener { if (!rebuildingTree) onJobSelected() }
        // The spinner of a running job or build is an animated icon inside a renderer
        jobTree.putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        buildsTable.putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        // The star beside a job is part of the row: a click on it marks or unmarks the favourite
        jobTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val job = starJobAt(e) ?: return
                setFavourite(job, !JenkinsSettings.getInstance().isFavouriteJob(job.fullName))
                e.consume()
            }
        })
        jobTree.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val over = starJobAt(e) != null
                jobTree.cursor = if (over) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                jobTree.toolTipText = if (over) "Click to add to or remove from the favourites" else null
            }
        })
        jobTree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                if (!rebuildingTree) expanded += key(event.path)
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {
                if (!rebuildingTree) expanded -= key(event.path)
            }
        })
        filterField.textEditor.emptyText.text = "Filter jobs"
        filterField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuildTree()
        })
        val jobsPanel = JPanel(BorderLayout()).apply {
            add(filterField, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(jobTree, true), BorderLayout.CENTER)
        }

        // Builds
        buildsTable.setShowGrid(false)
        buildsTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        buildsTable.emptyText.text = "Select a job"
        buildsTable.columnModel.getColumn(0).cellRenderer = BuildRenderer()
        buildsTable.columnModel.getColumn(0).preferredWidth = JBUI.scale(150)
        buildsTable.columnModel.getColumn(1).preferredWidth = JBUI.scale(80)
        buildsTable.columnModel.getColumn(2).preferredWidth = JBUI.scale(140)
        buildsTable.columnModel.getColumn(3).preferredWidth = JBUI.scale(90)
        buildsTable.columnModel.getColumn(4).preferredWidth = JBUI.scale(120)
        buildsTable.selectionModel.addListSelectionListener {
            if (!updatingTable && !it.valueIsAdjusting) onRowSelected()
        }

        // Build details
        summaryPane.editorKit = HTMLEditorKitBuilder.simple()
        summaryPane.isEditable = false
        summaryPane.border = JBUI.Borders.empty(6, 10)
        summaryPane.background = UIUtil.getListBackground()
        summaryPane.addHyperlinkListener(BrowserHyperlinkListener.INSTANCE)

        artifactsTable.setShowGrid(false)
        artifactsTable.emptyText.text = "No artifacts"
        artifactsTable.columnModel.getColumn(0).preferredWidth = JBUI.scale(260)
        artifactsTable.columnModel.getColumn(1).preferredWidth = JBUI.scale(80)
        artifactsTable.columnModel.getColumn(2).preferredWidth = JBUI.scale(260)
        val artifactActions = DefaultActionGroup().apply {
            add(action("Download...", "Download the selected artifacts to a folder", AllIcons.Actions.Download, { selectedArtifacts().isNotEmpty() }) {
                download(selectedArtifacts())
            })
            add(action("Download All...", "Download all artifacts of the build to a folder", AllIcons.Actions.MenuSaveall, { artifacts.isNotEmpty() }) {
                download(artifacts.toList())
            })
            add(action("Open in Browser", "Open the selected artifact in the browser", AllIcons.General.Web, { selectedArtifacts().size == 1 }) {
                selectedArtifacts().firstOrNull()?.let { a -> artifactUrl(a)?.let { BrowserUtil.browse(it) } }
            })
            add(action("Copy Link", "Copy the address of the selected artifacts", AllIcons.Actions.Copy, { selectedArtifacts().isNotEmpty() }) {
                copy(selectedArtifacts().mapNotNull { artifactUrl(it) }.joinToString("\n"))
            })
        }
        val artifactToolbar = ActionManager.getInstance().createActionToolbar("JenkinsBuilds.Artifacts", artifactActions, true)
        artifactToolbar.targetComponent = artifactsTable
        val artifactsPanel = JPanel(BorderLayout()).apply {
            add(artifactToolbar.component, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(artifactsTable, true), BorderLayout.CENTER)
        }

        tabs.addTab("Summary", ScrollPaneFactory.createScrollPane(summaryPane, true))
        tabs.addTab("Artifacts", artifactsPanel)
        searchField.textEditor.emptyText.text = "Search in the log (Enter: next, Shift+Enter: previous)"
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = searchConsole(0)
        })
        searchField.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode != KeyEvent.VK_ENTER) return
                searchConsole(if (e.isShiftDown) -1 else 1)
                e.consume()
            }
        })
        searchLabel.border = JBUI.Borders.empty(0, 8)
        val searchActions = DefaultActionGroup().apply {
            add(action("Previous Match", "Go to the previous match in the log", AllIcons.Actions.PreviousOccurence, { searchField.text.isNotEmpty() }) { searchConsole(-1) })
            add(action("Next Match", "Go to the next match in the log", AllIcons.Actions.NextOccurence, { searchField.text.isNotEmpty() }) { searchConsole(1) })
        }
        val searchToolbar = ActionManager.getInstance().createActionToolbar("JenkinsBuilds.ConsoleSearch", searchActions, true)
        searchToolbar.targetComponent = searchField
        val searchRow = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                add(searchLabel, BorderLayout.WEST)
                add(searchToolbar.component, BorderLayout.EAST)
            }, BorderLayout.EAST)
        }
        val consolePanel = JPanel(BorderLayout()).apply {
            add(searchRow, BorderLayout.NORTH)
            add(console.component, BorderLayout.CENTER)
        }
        tabs.addTab("Console", consolePanel)
        tabs.addChangeListener { if (tabs.selectedIndex == CONSOLE_TAB) startConsole() }

        buildsSplit.firstComponent = ScrollPaneFactory.createScrollPane(buildsTable, true)
        buildsSplit.secondComponent = tabs
        jobsSplit.firstComponent = jobsPanel
        jobsSplit.secondComponent = buildsSplit
        val remember = PropertyChangeListener { rememberProportions() }
        jobsSplit.addPropertyChangeListener(Splitter.PROP_PROPORTION, remember)
        buildsSplit.addPropertyChangeListener(Splitter.PROP_PROPORTION, remember)

        statusLabel.border = JBUI.Borders.empty(2, 8)
        statusLabel.setCopyable(true)
        // A long message must not force the tool window wider than it is docked
        statusLabel.minimumSize = Dimension(0, statusLabel.preferredSize.height)
        val content = JPanel(BorderLayout()).apply {
            add(jobsSplit, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        setContent(content)
        showSummaryHint("Select a build to see its details.")

        // Docked at the bottom the three parts stand side by side, docked left or right they are stacked.
        // Until the window has a size the dock side decides; afterwards the shape of the window does.
        applyLayout(!toolWindow.anchor.isHorizontal)
        content.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val width = content.width
                val height = content.height
                if (width <= 0 || height <= 0) return
                applyLayout(width < height || width < JBUI.scale(520))
            }
        })
    }

    /** Stacks the jobs, the builds and the build details ([vertical]) or puts them side by side. */
    private fun applyLayout(vertical: Boolean) {
        if (verticalLayout == vertical) return
        val state = JenkinsSettings.getInstance().state
        applyingLayout = true
        try {
            verticalLayout = vertical
            jobsSplit.orientation = vertical
            buildsSplit.orientation = vertical
            jobsSplit.proportion = (if (vertical) state.jobsSplitVertical else state.jobsSplitHorizontal).coerceIn(0.1f, 0.9f)
            buildsSplit.proportion = (if (vertical) state.buildsSplitVertical else state.buildsSplitHorizontal).coerceIn(0.1f, 0.9f)
        } finally {
            applyingLayout = false
        }
        revalidate()
        repaint()
    }

    /** Keeps the divider positions the user chose, separately for the stacked and the side-by-side layout. */
    private fun rememberProportions() {
        if (applyingLayout) return
        val vertical = verticalLayout ?: return
        val state = JenkinsSettings.getInstance().state
        if (vertical) {
            state.jobsSplitVertical = jobsSplit.proportion
            state.buildsSplitVertical = buildsSplit.proportion
        } else {
            state.jobsSplitHorizontal = jobsSplit.proportion
            state.buildsSplitHorizontal = buildsSplit.proportion
        }
    }

    private fun action(text: String, description: String, icon: Icon, enabled: () -> Boolean, run: () -> Unit): AnAction =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = !disposed && enabled()
            }

            override fun actionPerformed(e: AnActionEvent) {
                if (!disposed) run()
            }
        }

    private fun toggle(
        text: String, description: String, icon: Icon,
        enabled: () -> Boolean, selected: () -> Boolean, set: (Boolean) -> Unit,
    ): AnAction = object : DumbAwareToggleAction(text, description, icon) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = !disposed && selected()
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (!disposed) set(state)
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = !disposed && enabled()
        }
    }

    /** The job the tree selection points at; folders and groups give null. */
    private fun selectedTreeJob(): JobNode? =
        ((jobTree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? JobNode)?.takeUnless { it.container }

    /** The job whose star is under the mouse, or null. */
    private fun starJobAt(e: MouseEvent): JobNode? {
        val path = jobTree.getClosestPathForLocation(e.x, e.y) ?: return null
        val bounds = jobTree.getPathBounds(path) ?: return null
        if (e.y < bounds.y || e.y >= bounds.y + bounds.height) return null
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        val job = (node.userObject as? JobNode)?.takeUnless { it.container } ?: return null
        val row = jobTree.getRowForPath(path)
        val cell = jobTree.cellRenderer.getTreeCellRendererComponent(jobTree, node, jobTree.isRowSelected(row), false, true, row, false)
            as? SimpleColoredComponent ?: return null
        return if (cell.getFragmentTagAt(e.x - bounds.x) === STAR_TAG) job else null
    }

    private fun setFavourite(job: JobNode, favourite: Boolean) {
        JenkinsSettings.getInstance().setFavouriteJob(job.fullName, favourite)
        if (favourite) expanded += "g:$FAVOURITES"
        rebuildTree()
        status(if (favourite) "${job.fullName} added to the favourites" else "${job.fullName} removed from the favourites")
    }

    // A function, not a property: the popup is built from the init block, before a property down here would be set
    private fun createFavouriteAction(): AnAction {
        return toggle(
            "Favourite", "Mark the selected job as favourite", AllIcons.Nodes.Favorite,
            { selectedTreeJob() != null },
            { selectedTreeJob()?.let { JenkinsSettings.getInstance().isFavouriteJob(it.fullName) } == true },
        ) { favourite -> selectedTreeJob()?.let { setFavourite(it, favourite) } }
    }

    /** Shows [tree] as if the server had answered with it. For the tests. */
    internal fun showTree(tree: ServerTree) {
        server = tree
        rebuildTree()
    }

    private fun openSettings() {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, JenkinsConfigurable::class.java)
    }

    private fun copy(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        status("Copied: " + text.lineSequence().first() + if (text.contains('\n')) " ..." else "")
    }

    // ---- background work ----------------------------------------------------------------------------------------------

    private fun busy(slot: String): Boolean = slots[slot]?.future != null

    /** Cancels what is running in [slot]: its answer will be ignored. */
    private fun cancel(slot: String) {
        val s = slots[slot] ?: return
        s.generation++
        s.future?.cancel(false)
        s.future = null
    }

    /**
     * Runs [work] on a pooled thread and hands the result to [done] on the UI thread, unless a newer call of the same
     * [slot] was started or the slot was cancelled in the meantime. A failure goes to [failed], by default to the
     * status line.
     */
    private fun <T> load(
        slot: String,
        work: (JenkinsClient) -> T,
        failed: (Throwable) -> Unit = { showError(it) },
        done: (T) -> Unit,
    ) {
        val s = slots.getOrPut(slot) { Slot() }
        s.future?.cancel(false)
        val generation = ++s.generation
        s.future = AppExecutorUtil.getAppExecutorService().submit {
            val result = try {
                Result.success(work(JenkinsSettings.getInstance().client()))
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed || s.generation != generation) return@invokeLater
                s.future = null
                result.fold(done, failed)
            }, ModalityState.any())
        }
    }

    private fun status(text: String) {
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        statusLabel.text = text.ifEmpty { " " }
        statusLabel.toolTipText = null
    }

    private fun message(error: Throwable): String =
        JenkinsParser.htmlToText(error.message ?: error.javaClass.simpleName, 500)

    private fun showError(error: Throwable) {
        val text = message(error)
        statusLabel.foreground = JBColor.RED
        statusLabel.text = text
        statusLabel.toolTipText = text
    }

    // ---- jobs ---------------------------------------------------------------------------------------------------------

    private fun refreshAll() {
        loadTree(quiet = false)
        if (currentJob != null) reloadJob(quiet = false)
    }

    private fun loadTree(quiet: Boolean) {
        if (!JenkinsSettings.getInstance().configured) {
            server = null
            rebuildTree()
            jobTree.emptyText.clear()
            jobTree.emptyText.appendLine("The Jenkins server is not set up.")
            jobTree.emptyText.appendLine("Open settings", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { openSettings() }
            status("")
            return
        }
        if (!quiet) {
            status("Loading jobs from ${JenkinsSettings.getInstance().url} ...")
            if (server == null) jobTree.emptyText.text = "Loading..."
        }
        load("tree", { it.loadTree() }, failed = { error ->
            showError(error)
            if (server == null) {
                jobTree.emptyText.clear()
                jobTree.emptyText.appendLine("The jobs could not be loaded.", SimpleTextAttributes.ERROR_ATTRIBUTES, null)
                for (line in wrap(message(error), 60)) jobTree.emptyText.appendLine(line)
                jobTree.emptyText.appendLine("Open settings", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { openSettings() }
            }
        }) { tree ->
            server = tree
            rebuildTree()
            if (!quiet) status("${countJobs(tree.jobs)} jobs, ${tree.views.size} views")
        }
    }

    private fun wrap(text: String, width: Int): List<String> {
        val lines = ArrayList<String>()
        var line = StringBuilder()
        for (word in text.split(' ')) {
            if (line.isNotEmpty() && line.length + word.length + 1 > width) {
                lines += line.toString()
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines.take(6)
    }

    private fun countJobs(jobs: List<JobNode>): Int = jobs.sumOf { if (it.container) countJobs(it.children) else 1 }

    private fun key(path: TreePath): String = path.path.drop(1).joinToString("\u0000") { node ->
        when (val o = (node as DefaultMutableTreeNode).userObject) {
            is JobNode -> "j:" + o.fullName
            is Group -> "g:" + o.name
            else -> o.toString()
        }
    }

    /** Fills the tree from [server] and the filter, keeping expansion and selection. */
    private fun rebuildTree() {
        val tree = server
        val query = filterField.text.trim()
        val selectedJob = currentJob?.fullName
        val selectedKey = jobTree.selectionPath?.let { key(it) }
        rebuildingTree = true
        try {
            treeRoot.removeAllChildren()
            val settings = JenkinsSettings.getInstance()
            val onlyFavourites = settings.onlyFavouriteJobs
            if (tree != null) {
                // The favourites come first, as a flat list in the order they were added
                val known = HashMap<String, JobNode>()
                fun collect(jobs: List<JobNode>) {
                    for (job in jobs) if (job.container) collect(job.children) else known.putIfAbsent(job.fullName, job)
                }
                collect(tree.jobs)
                fun collectView(view: ViewNode) {
                    collect(view.jobs)
                    view.sections.forEach { collect(it.jobs) }
                    view.views.forEach { collectView(it) }
                }
                tree.views.forEach { collectView(it) }
                addGroup(treeRoot, Group(FAVOURITES, true), settings.favouriteJobs.mapNotNull { known[it] }, query)
                if (firstTree) expanded += "g:$FAVOURITES"

                if (!onlyFavourites) {
                    val all = Group(tree.primaryView?.takeIf { it.isNotBlank() } ?: "All", true)
                    val before = treeRoot.childCount
                    addGroup(treeRoot, all, tree.jobs, query)
                    val allNode = if (treeRoot.childCount > before) treeRoot.getChildAt(before) else null
                    for (view in tree.views) {
                        // The primary view lists every job again: the group above already shows them, with their folders
                        val isAll = view.name.equals(tree.primaryView, ignoreCase = true) || view.name.equals("all", ignoreCase = true)
                        if (isAll && view.sections.isEmpty() && view.views.isEmpty()) continue
                        addView(treeRoot, view, query)
                    }
                    if (firstTree) {
                        firstTree = false
                        // Start with the views open; the group with everything only when there is no other view
                        val views = treeRoot.childCount - before
                        for (i in 0 until treeRoot.childCount) {
                            val node = treeRoot.getChildAt(i) as DefaultMutableTreeNode
                            if (node === allNode && views > 1) continue
                            remember(node, 2)
                        }
                    }
                }
            }
            treeModel.reload()
            if (tree != null && treeRoot.childCount == 0) {
                jobTree.emptyText.text = when {
                    onlyFavourites && settings.favouriteJobs.isEmpty() -> "No favourite jobs yet: click the star beside a job"
                    query.isNotEmpty() -> "No job matches \"$query\""
                    onlyFavourites -> "The favourite jobs are not on this server"
                    else -> "The server has no jobs you may see"
                }
            }
            expand(treeRoot, query.isNotEmpty())
            val target = find(treeRoot, selectedKey, selectedJob)
            if (target != null) {
                jobTree.selectionPath = TreePath(target.path)
                (target.userObject as? JobNode)?.let { if (currentJob?.fullName == it.fullName) currentJob = it }
            }
        } finally {
            rebuildingTree = false
        }
    }

    private fun remember(node: DefaultMutableTreeNode, levels: Int) {
        if (levels <= 0 || node.userObject !is Group) return
        expanded += key(TreePath(node.path))
        for (i in 0 until node.childCount) remember(node.getChildAt(i) as DefaultMutableTreeNode, levels - 1)
    }

    private fun expand(node: DefaultMutableTreeNode, all: Boolean) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            if (child.isLeaf) continue
            val path = TreePath(child.path)
            if (all || key(path) in expanded) {
                jobTree.expandPath(path)
                expand(child, all)
            }
        }
    }

    private fun find(node: DefaultMutableTreeNode, wantedKey: String?, wantedJob: String?): DefaultMutableTreeNode? {
        var byJob: DefaultMutableTreeNode? = null
        val nodes = node.depthFirstEnumeration()
        while (nodes.hasMoreElements()) {
            val n = nodes.nextElement() as DefaultMutableTreeNode
            if (n === treeRoot) continue
            if (wantedKey != null && key(TreePath(n.path)) == wantedKey) return n
            val job = n.userObject as? JobNode
            if (byJob == null && wantedJob != null && job != null && !job.container && job.fullName == wantedJob) byJob = n
        }
        return byJob
    }

    private fun addView(parent: DefaultMutableTreeNode, view: ViewNode, query: String) {
        val node = DefaultMutableTreeNode(Group(view.name, true))
        if (view.sections.isNotEmpty()) {
            for (section in view.sections) addGroup(node, Group(section.name, false), section.jobs, query)
        } else {
            for (job in view.jobs) addJob(node, job, query)
        }
        for (child in view.views) addView(node, child, query)
        if (node.childCount > 0) parent.add(node)
    }

    private fun addGroup(parent: DefaultMutableTreeNode, group: Group, jobs: List<JobNode>, query: String) {
        val node = DefaultMutableTreeNode(group)
        for (job in jobs) addJob(node, job, query)
        if (node.childCount > 0) parent.add(node)
    }

    private fun matches(job: JobNode, query: String): Boolean =
        query.isEmpty() || job.fullName.contains(query, ignoreCase = true) || job.label.contains(query, ignoreCase = true)

    private fun addJob(parent: DefaultMutableTreeNode, job: JobNode, query: String) {
        val node = DefaultMutableTreeNode(job)
        if (job.container) {
            // A folder that matches is shown with everything in it
            val below = if (matches(job, query)) "" else query
            for (child in job.children) addJob(node, child, below)
            if (node.childCount > 0 || matches(job, query)) parent.add(node)
        } else if (matches(job, query)) {
            parent.add(node)
        }
    }

    private fun onJobSelected() {
        val job = (jobTree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? JobNode
        if (job == null || job.container) return
        if (job.fullName == currentJob?.fullName) {
            currentJob = job
            return
        }
        currentJob = job
        jobDetails = null
        selectWhenListed = null
        clearBuild("Select a build to see its details.")
        rows.clear()
        buildsModel.fireTableDataChanged()
        buildsTable.emptyText.text = "Loading the builds of ${job.label}..."
        reloadJob(quiet = false)
    }

    // ---- builds of a job ----------------------------------------------------------------------------------------------

    private fun reloadJob(quiet: Boolean) {
        val job = currentJob ?: return
        if (!quiet) status("Loading ${job.fullName} ...")
        val limit = JenkinsSettings.getInstance().buildsLimit
        load("job", { it.loadJob(job.fullName, limit) }, failed = { error ->
            showError(error)
            if (jobDetails == null) buildsTable.emptyText.text = "The builds could not be loaded"
        }) { details ->
            if (currentJob?.fullName != job.fullName) return@load
            val wasRunning = jobDetails?.builds?.any { it.building } == true
            jobDetails = details
            // Builds I started elsewhere (in the browser, say) count as mine too
            val me = JenkinsSettings.getInstance().username
            if (me.isNotEmpty()) {
                details.builds.filter { it.building && it.startedBy.equals(me, ignoreCase = true) }.forEach { watchBuild(job.fullName, it.number) }
                updateBadge()
            }
            fillRows()
            if (!quiet) status("${job.fullName}: ${details.builds.size} builds" + if (details.params.isNotEmpty()) ", ${details.params.size} parameters" else "")
            // A build started or ended: the status icons in the job tree are out of date
            if (wasRunning != details.builds.any { it.building } && !busy("tree")) loadTree(quiet = true)
        }
    }

    private fun rowKey(row: Row?): String? = when (row) {
        is Row.Queued -> "q" + row.id
        is Row.Build -> "b" + row.ref.number
        null -> null
    }

    private fun selectedRow(): Row? = buildsTable.selectedRow.takeIf { it in rows.indices }?.let { rows[it] }

    /** Rebuilds the table from the job details and the watched queue items, keeping the selection. */
    private fun fillRows() {
        val job = currentJob ?: return
        val details = jobDetails
        var wanted = rowKey(selectedRow())
        selectWhenListed?.let { number ->
            if (details?.builds?.any { it.number == number } == true) {
                wanted = "b$number"
                selectWhenListed = null
            }
        }
        val queued = LinkedHashMap<Long, Row.Queued>()
        for ((id, item) in tracked) if (item.job == job.fullName) queued[id] = Row.Queued(id, item.why)
        details?.queueItem?.let { if (it.id !in queued) queued[it.id] = Row.Queued(it.id, it.why) }

        updatingTable = true
        try {
            rows.clear()
            rows += queued.values
            details?.builds?.forEach { rows += Row.Build(it) }
            buildsModel.fireTableDataChanged()
            buildsTable.emptyText.text = if (details == null) "Loading..." else "No builds yet"
            val index = rows.indexOfFirst { rowKey(it) == wanted }
            if (index >= 0) buildsTable.selectionModel.setSelectionInterval(index, index)
        } finally {
            updatingTable = false
        }
        // The selection may now point at another build than the one shown on the right
        val row = selectedRow()
        if (row is Row.Build && row.ref.number != currentBuild) onRowSelected()
        if (row is Row.Queued && currentBuild != null) onRowSelected()
    }

    private fun onRowSelected() {
        when (val row = selectedRow()) {
            is Row.Build -> {
                if (row.ref.number == currentBuild) return
                clearBuild("Loading ${row.ref.title}...")
                currentBuild = row.ref.number
                reloadBuild(quiet = false)
                if (tabs.selectedIndex == CONSOLE_TAB) startConsole()
            }
            is Row.Queued -> clearBuild("Waiting in the queue. " + (row.why ?: ""))
            null -> {}
        }
    }

    // ---- one build ----------------------------------------------------------------------------------------------------

    private fun showSummaryHint(text: String) {
        summaryPane.text = "<html><body><p><font color='gray'>${Format.escape(text)}</font></p></body></html>"
    }

    private fun clearBuild(hint: String) {
        cancel("build")
        cancel("console")
        cancel("sizes")
        consoleSession++
        consoleShown = null
        currentBuild = null
        buildDetails = null
        artifacts.clear()
        artifactSizes.clear()
        artifactsModel.fireTableDataChanged()
        tabs.setTitleAt(ARTIFACTS_TAB, "Artifacts")
        console.clear()
        consolePending = ""
        clearSearchMarks()
        showSummaryHint(hint)
    }

    private fun reloadBuild(quiet: Boolean) {
        val job = currentJob ?: return
        val number = currentBuild ?: return
        load("build", { it.loadBuild(job.fullName, number) }, failed = { error ->
            showError(error)
            if (buildDetails == null) showSummaryHint(message(error))
        }) { details ->
            if (currentJob?.fullName != job.fullName || currentBuild != number) return@load
            val first = buildDetails == null
            val finished = buildDetails?.building == true && !details.building
            buildDetails = details
            val scroll = summaryPane.visibleRect
            summaryPane.text = Format.summaryHtml(job.fullName, details)
            if (first) summaryPane.caretPosition = 0 else ApplicationManager.getApplication().invokeLater { summaryPane.scrollRectToVisible(scroll) }
            if (artifacts != details.artifacts) {
                artifacts.clear()
                artifacts += details.artifacts
                artifactsModel.fireTableDataChanged()
                loadArtifactSizes(job.fullName, number, details.artifacts)
            }
            tabs.setTitleAt(ARTIFACTS_TAB, if (artifacts.isEmpty()) "Artifacts" else "Artifacts (${artifacts.size})")
            if (!quiet) status("")
            if (finished) reloadJob(quiet = true)
        }
    }

    /** The JSON API does not tell the size of an artifact: ask for it with HEAD requests. */
    private fun loadArtifactSizes(fullName: String, number: Int, list: List<Artifact>) {
        if (list.isEmpty()) return
        val base = JenkinsSettings.getInstance().url
        val wanted = list.take(MAX_SIZED_ARTIFACTS)
        load("sizes", { client ->
            val sizes = HashMap<String, Long>()
            for (artifact in wanted) {
                val size = try {
                    client.contentLength(JenkinsUrls.artifactUrl(base, fullName, number, artifact.relativePath))
                } catch (_: Exception) {
                    null
                }
                if (size != null) sizes[artifact.relativePath] = size
            }
            sizes
        }, failed = {}) { sizes ->
            artifactSizes.putAll(sizes)
            artifactsModel.fireTableRowsUpdated(0, maxOf(0, artifacts.size - 1))
        }
    }

    private fun startConsole() {
        val job = currentJob ?: return
        val number = currentBuild ?: return
        val wanted = job.fullName to number
        if (consoleShown == wanted) return
        consoleShown = wanted
        console.clear()
        consolePending = ""
        clearSearchMarks()
        followConsole(job.fullName, number, 0, ++consoleSession)
    }

    /**
     * Prints a piece of the log, coloured line by line. A line that is not complete yet waits in [consolePending]
     * for its rest, unless [last] says nothing more will come.
     */
    private fun printConsole(text: String, last: Boolean) {
        val all = consolePending + text
        val cut = if (last) all.length else all.lastIndexOf('\n') + 1
        consolePending = all.substring(cut)
        for ((kind, piece) in ConsoleText.runs(all.substring(0, cut))) {
            console.print(piece, when (kind) {
                ConsoleText.Kind.ERROR -> ConsoleViewContentType.ERROR_OUTPUT
                ConsoleText.Kind.WARNING -> CONSOLE_WARNING
                ConsoleText.Kind.SUCCESS -> CONSOLE_SUCCESS
                ConsoleText.Kind.STEP -> CONSOLE_STEP
                ConsoleText.Kind.NORMAL -> ConsoleViewContentType.NORMAL_OUTPUT
            })
        }
    }

    // ---- search in the console ----------------------------------------------------------------------------------------

    private fun consoleEditor(): Editor? = (console as? ConsoleViewImpl)?.editor

    private fun clearSearchMarks() {
        val editor = consoleEditor()
        if (editor != null) searchMarks.forEach { if (it.isValid) editor.markupModel.removeHighlighter(it) }
        searchMarks.clear()
        searchLabel.text = ""
    }

    /**
     * Marks every place the text of the search field occurs in the log and moves to the next one after the caret
     * ([step] 1), the one before it (-1) or the first one at or after it (0).
     */
    private fun searchConsole(step: Int) {
        clearSearchMarks()
        val editor = consoleEditor() ?: return
        val query = searchField.text
        if (query.isEmpty()) return
        val found = ConsoleText.matches(editor.document.immutableCharSequence, query)
        if (found.isEmpty()) {
            searchLabel.foreground = JBColor.RED
            searchLabel.text = "No matches"
            return
        }
        for (offset in found.take(MAX_SEARCH_MARKS)) {
            searchMarks += editor.markupModel.addRangeHighlighter(
                EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES, offset, offset + query.length,
                HighlighterLayer.SELECTION - 1, HighlighterTargetArea.EXACT_RANGE,
            )
        }
        val selected = editor.selectionModel.selectionStart.takeIf { editor.selectionModel.hasSelection() }
        val caret = selected ?: editor.caretModel.offset
        val index = when {
            step > 0 -> found.indexOfFirst { it > caret }.takeIf { it >= 0 } ?: 0
            step < 0 -> found.indexOfLast { it < caret }.takeIf { it >= 0 } ?: found.lastIndex
            else -> found.indexOfFirst { it >= caret }.takeIf { it >= 0 } ?: 0
        }
        val offset = found[index]
        editor.caretModel.moveToOffset(offset)
        editor.selectionModel.setSelection(offset, offset + query.length)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        searchLabel.foreground = UIUtil.getContextHelpForeground()
        searchLabel.text = "${index + 1} of ${found.size}" + if (found.size >= 5000) "+" else ""
    }

    /** Reads the log from [start] and, while the build still writes, comes back for more. */
    private fun followConsole(fullName: String, number: Int, start: Long, session: Int) {
        load("console", { it.console(fullName, number, start) }, failed = { error ->
            if (session == consoleSession) console.print("\n" + message(error) + "\n", ConsoleViewContentType.ERROR_OUTPUT)
        }) { chunk ->
            if (session != consoleSession) return@load
            printConsole(chunk.text, last = !chunk.more)
            if (chunk.more) {
                AppExecutorUtil.getAppScheduledExecutorService().schedule({
                    ApplicationManager.getApplication().invokeLater({
                        if (!disposed && session == consoleSession) followConsole(fullName, number, chunk.nextStart, session)
                    }, ModalityState.any())
                }, CONSOLE_POLL_MILLIS, TimeUnit.MILLISECONDS)
            } else if (buildDetails?.building == true) {
                reloadBuild(quiet = true)
            }
        }
    }

    // ---- refresh while something runs ---------------------------------------------------------------------------------

    private fun tick() {
        val visible = toolWindow.isVisible
        if (visible && unseen.isNotEmpty()) {
            // The window is open: the finished builds have been seen
            unseen.clear()
            updateBadge()
        }
        val now = System.currentTimeMillis()
        if (now - lastPoll < JenkinsSettings.getInstance().refreshSeconds * 1000L) return
        lastPoll = now

        // My own builds are followed while the window is hidden too: the badge on its button tells how they go
        if (tracked.isNotEmpty() && !busy("queue")) pollQueue()
        if (watched.isNotEmpty() && !busy("watch")) pollWatched()
        if (!visible) return
        val job = currentJob ?: return
        val details = jobDetails
        val active = (details != null && (details.queueItem != null || details.builds.any { it.building })) ||
            tracked.values.any { it.job == job.fullName }
        if (active && !busy("job")) reloadJob(quiet = true)
        if (buildDetails?.building == true && !busy("build")) reloadBuild(quiet = true)
    }

    /** Asks how the builds I started are doing; a finished one leaves the list and, when unseen, turns the badge green. */
    private fun pollWatched() {
        val list = watched.values.toList()
        load("watch", { client ->
            list.associateWith { w ->
                try {
                    client.loadBuild(w.job, w.number)
                } catch (_: Exception) {
                    null
                }
            }
        }, failed = {}) { results ->
            for ((watch, details) in results) {
                val key = watchKey(watch.job, watch.number)
                if (details == null) {
                    // The build cannot be read (deleted, or the server is away): give up after a while
                    if (++watch.failures >= 10) watched.remove(key)
                    continue
                }
                watch.failures = 0
                if (details.building) continue
                watched.remove(key)
                val result = Format.result(details.result, false)
                if (!toolWindow.isVisible) {
                    unseen += details.result ?: ""
                    val type = if (details.result == "SUCCESS") NotificationType.INFORMATION else NotificationType.WARNING
                    NotificationGroupManager.getInstance().getNotificationGroup("Jenkins Builds")
                        .createNotification("Jenkins build finished", "${watch.job} ${details.title}: $result", type)
                        .notify(project)
                } else {
                    status("${watch.job} ${details.title}: $result")
                }
            }
            updateBadge()
        }
    }

    private fun watchKey(job: String, number: Int): String = "$job#$number"

    private fun watchBuild(job: String, number: Int) {
        watched.putIfAbsent(watchKey(job, number), Watched(job, number))
    }

    /** Puts the badge on the button of the tool window: blue while my builds run or wait, green or red for unseen results. */
    private fun updateBadge() {
        val color = when (badgeFor(unseen, tracked.isNotEmpty() || watched.isNotEmpty())) {
            Badge.NONE -> null
            Badge.RUNNING -> JBColor(0x3574F0, 0x548AF7)
            Badge.FINISHED -> JBColor(0x208A3C, 0x5FAD65)
            Badge.FAILED -> JBColor(0xDB3B4B, 0xE55765)
        }
        if (color == shownBadge) return
        shownBadge = color
        toolWindow.setIcon(if (color == null) baseIcon else ExecutionUtil.getIndicator(baseIcon, baseIcon.iconWidth, baseIcon.iconHeight, color))
    }

    private fun pollQueue() {
        val ids = tracked.keys.toList()
        load("queue", { client -> ids.associateWith { client.queueItem(it) } }) { items ->
            var changed = false
            for ((id, item) in items) {
                val watch = tracked[id] ?: continue
                when {
                    item == null -> tracked.remove(id)
                    item.cancelled -> {
                        tracked.remove(id)
                        status("${watch.job}: the queued build was cancelled")
                    }
                    item.executableNumber != null -> {
                        tracked.remove(id)
                        watchBuild(watch.job, item.executableNumber)
                        status("${watch.job}: build #${item.executableNumber} started")
                        if (currentJob?.fullName == watch.job) {
                            val row = selectedRow()
                            if (row == null || (row is Row.Queued && row.id == id)) selectWhenListed = item.executableNumber
                        }
                    }
                    else -> {
                        if (watch.why != item.why) changed = true
                        watch.why = item.why
                        continue
                    }
                }
                changed = true
                if (currentJob?.fullName == watch.job) reloadJob(quiet = true)
            }
            if (changed) fillRows()
            updateBadge()
        }
    }

    // ---- actions ------------------------------------------------------------------------------------------------------

    private fun canBuild(): Boolean = currentJob != null && jobDetails?.buildable == true

    private fun canStop(): Boolean = when (val row = selectedRow()) {
        is Row.Queued -> true
        is Row.Build -> row.ref.building || (buildDetails?.building == true && buildDetails?.number == row.ref.number)
        null -> false
    }

    private fun selectedUrl(): String? {
        val base = JenkinsSettings.getInstance().url.takeIf { it.isNotEmpty() } ?: return null
        val job = currentJob ?: return "$base/"
        val number = currentBuild ?: return JenkinsUrls.jobUrl(base, job.fullName)
        return JenkinsUrls.buildUrl(base, job.fullName, number)
    }

    private fun build(overrides: Map<String, String>?, titlePrefix: String = "Build") {
        val job = currentJob ?: return
        val details = jobDetails ?: return
        var definitions = details.params
        if (overrides != null) {
            // Parameters the build had but the job no longer declares are still sent, as plain text
            val known = definitions.map { it.name }.toSet()
            definitions = definitions + overrides.keys.filter { it !in known }
                .map { ParamDef(it, ParamKind.STRING, null, null, emptyList(), null) }
        }
        val parameters: List<Pair<String, String>>?
        if (definitions.isEmpty()) {
            val answer = Messages.showOkCancelDialog(
                project, "Start a build of ${job.fullName}?", "$titlePrefix ${job.label}", "Build", Messages.getCancelButton(), Messages.getQuestionIcon(),
            )
            if (answer != Messages.OK) return
            parameters = null
        } else {
            val dialog = BuildParametersDialog(project, job.fullName, "$titlePrefix ${job.fullName}", definitions, overrides ?: emptyMap())
            if (!dialog.showAndGet()) return
            parameters = dialog.values()
        }
        status("Starting ${job.fullName} ...")
        load("action" + actionCounter++, { it.triggerBuild(job.fullName, parameters) }) { id ->
            if (id != null) {
                tracked[id] = Tracked(job.fullName, "Waiting in the queue")
                status("${job.fullName}: queued (item $id)")
            } else {
                status("${job.fullName}: the build was requested")
            }
            lastPoll = 0
            updateBadge()
            if (currentJob?.fullName == job.fullName) {
                fillRows()
                if (id != null) {
                    val index = rows.indexOfFirst { it is Row.Queued && it.id == id }
                    if (index >= 0) buildsTable.selectionModel.setSelectionInterval(index, index)
                }
                reloadJob(quiet = true)
            }
        }
    }

    private fun rebuild() {
        val details = buildDetails ?: return
        val secret = jobDetails?.params?.filter { it.kind == ParamKind.PASSWORD }?.map { it.name }?.toSet() ?: emptySet()
        build(details.parameters.filter { it.first !in secret }.toMap(), "Rebuild ${details.title}:")
    }

    private fun stop() {
        val job = currentJob ?: return
        when (val row = selectedRow()) {
            is Row.Queued -> {
                status("Cancelling the queued build ...")
                load("action" + actionCounter++, { it.cancelQueueItem(row.id) }) {
                    tracked.remove(row.id)
                    updateBadge()
                    status("${job.fullName}: the queued build was cancelled")
                    if (currentJob?.fullName == job.fullName) {
                        fillRows()
                        reloadJob(quiet = true)
                    }
                }
            }
            is Row.Build -> {
                val answer = Messages.showOkCancelDialog(
                    project, "Stop ${job.fullName} ${row.ref.title}?", "Stop Build", "Stop", Messages.getCancelButton(), Messages.getWarningIcon(),
                )
                if (answer != Messages.OK) return
                status("Stopping ${row.ref.title} ...")
                load("action" + actionCounter++, { it.stopBuild(job.fullName, row.ref.number) }) {
                    status("${job.fullName} ${row.ref.title}: stop requested")
                    lastPoll = 0
                    if (currentJob?.fullName == job.fullName) reloadJob(quiet = true)
                }
            }
            null -> {}
        }
    }

    // ---- artifacts ----------------------------------------------------------------------------------------------------

    private fun selectedArtifacts(): List<Artifact> =
        artifactsTable.selectedRows.filter { it in artifacts.indices }.map { artifacts[it] }

    private fun artifactUrl(artifact: Artifact): String? {
        val job = currentJob ?: return null
        val number = currentBuild ?: return null
        return JenkinsUrls.artifactUrl(JenkinsSettings.getInstance().url, job.fullName, number, artifact.relativePath)
    }

    /** `name.war`, or `name (1).war` and so on when a file of that name is already in [directory]. */
    private fun freeName(directory: File, name: String): File {
        var candidate = File(directory, name)
        val stem = name.substringBeforeLast('.', name)
        val extension = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (candidate.exists()) candidate = File(directory, "$stem (${i++})$extension")
        return candidate
    }

    private fun download(list: List<Artifact>) {
        if (list.isEmpty()) return
        val job = currentJob ?: return
        val number = currentBuild ?: return
        val descriptor = FileChooserDescriptorFactory.singleDir().withTitle("Download Artifacts To")
        val folder = FileChooser.chooseFile(descriptor, project, null) ?: return
        val directory = File(folder.path)
        val base = JenkinsSettings.getInstance().url
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Downloading Jenkins artifacts", true) {
            private val saved = ArrayList<File>()
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    val client = JenkinsSettings.getInstance().client()
                    for ((i, artifact) in list.withIndex()) {
                        indicator.checkCanceled()
                        indicator.text = "${artifact.fileName} (${i + 1} of ${list.size})"
                        val target = freeName(directory, File(artifact.fileName).name)
                        client.download(JenkinsUrls.artifactUrl(base, job.fullName, number, artifact.relativePath), target, indicator)
                        saved += target
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    failure = JenkinsParser.htmlToText(e.message ?: e.javaClass.simpleName, 400)
                }
            }

            override fun onFinished() {
                val group = NotificationGroupManager.getInstance().getNotificationGroup("Jenkins Builds")
                val error = failure
                val text = when {
                    error != null -> "Download failed after ${saved.size} of ${list.size} files: $error"
                    saved.size == 1 -> "${saved[0].name} saved to ${directory.path}"
                    else -> "${saved.size} of ${list.size} files saved to ${directory.path}"
                }
                val notification = group.createNotification("Jenkins artifacts", text, if (error != null) NotificationType.ERROR else NotificationType.INFORMATION)
                if (saved.isNotEmpty()) {
                    notification.addAction(NotificationAction.createSimple("Show in folder") { RevealFileAction.openFile(saved[0]) })
                }
                notification.notify(project)
            }
        })
    }

    // ---- models and renderers -----------------------------------------------------------------------------------------

    private inner class BuildsModel : AbstractTableModel() {
        private val columns = arrayOf("Build", "Result", "Started", "Duration", "Started by")

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (val row = rows[rowIndex]) {
            is Row.Queued -> when (columnIndex) {
                0 -> row
                1 -> "Queued"
                4 -> row.why ?: ""
                else -> ""
            }
            is Row.Build -> when (columnIndex) {
                0 -> row
                1 -> Format.result(row.ref.result, row.ref.building)
                2 -> Format.dateTime(row.ref.timestamp)
                3 -> if (row.ref.building) "running " + Format.duration(System.currentTimeMillis() - row.ref.timestamp) else Format.duration(row.ref.duration)
                else -> row.ref.startedBy ?: ""
            }
        }
    }

    private inner class ArtifactsModel : AbstractTableModel() {
        private val columns = arrayOf("Name", "Size", "Path")

        override fun getRowCount(): Int = artifacts.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val artifact = artifacts[rowIndex]
            return when (columnIndex) {
                0 -> artifact.fileName
                1 -> artifactSizes[artifact.relativePath]?.let { Format.size(it) } ?: ""
                else -> artifact.relativePath
            }
        }
    }

    private class BuildRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            when (value) {
                is Row.Queued -> {
                    icon = AllIcons.Actions.Pause
                    append("Queued", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is Row.Build -> {
                    icon = resultIcon(value.ref.result, value.ref.building, true)
                    append(value.ref.title)
                    if (value.ref.title != "#${value.ref.number}") append("  #${value.ref.number}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }

    private class JobRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is Group -> {
                    icon = if (item.view) AllIcons.Nodes.Folder else AllIcons.Nodes.Package
                    append(item.name, if (item.view) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                is JobNode -> {
                    if (item.container) {
                        icon = AllIcons.Nodes.Folder
                        append(item.label)
                    } else {
                        icon = colorIcon(item.color)
                        append(item.label)
                        if (JenkinsSettings.getInstance().isFavouriteJob(item.fullName)) {
                            append("  ★ ", STAR, STAR_TAG)
                        } else {
                            append("  ☆ ", SimpleTextAttributes.GRAYED_ATTRIBUTES, STAR_TAG)
                        }
                        item.lastBuild?.let { last ->
                            val result = Format.result(last.result, last.building)
                            val suffix = if (result == "Success") "" else ", " + result.lowercase()
                            append("${last.title}$suffix", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                }
                else -> append(value?.toString() ?: "")
            }
        }
    }

    companion object {
        private const val ARTIFACTS_TAB = 1
        private const val CONSOLE_TAB = 2
        private const val CONSOLE_POLL_MILLIS = 1500L
        private const val MAX_SEARCH_MARKS = 2000

        private fun consoleType(name: String, color: JBColor): ConsoleViewContentType =
            ConsoleViewContentType(name, TextAttributes(color, null, null, null, Font.PLAIN))

        private val CONSOLE_SUCCESS = consoleType("JENKINS_BUILDS_SUCCESS", JBColor(0x1F7A36, 0x5FB865))
        private val CONSOLE_WARNING = consoleType("JENKINS_BUILDS_WARNING", JBColor(0xA36B00, 0xE0A040))
        private val CONSOLE_STEP = consoleType("JENKINS_BUILDS_STEP", JBColor(0x2470B3, 0x6CA5E0))
        private const val MAX_SIZED_ARTIFACTS = 50
        private const val FAVOURITES = "Favourites"

        /**
         * The badge of the tool window button. An unseen result wins over a running build: green when every unseen
         * build succeeded, red when one did not; blue while a build of mine runs or waits ([active]).
         */
        fun badgeFor(unseen: Collection<String>, active: Boolean): Badge = when {
            unseen.any { it != "SUCCESS" } -> Badge.FAILED
            unseen.isNotEmpty() -> Badge.FINISHED
            active -> Badge.RUNNING
            else -> Badge.NONE
        }

        /** Marks the star in a job row, so a click on it can be told from a click on the name. */
        private val STAR_TAG = Any()
        private val STAR = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0xD9A300, 0xE5C04B))

        /** The icon for a Jenkins ball colour: `blue`, `red`, `yellow`, `aborted`, `disabled`, `notbuilt`, each also as `_anime`. */
        private fun colorIcon(color: String?): Icon {
            if (color == null) return AllIcons.RunConfigurations.TestUnknown
            if (color.endsWith("_anime")) return AnimatedIcon.Default.INSTANCE
            return when (color) {
                "blue", "green" -> AllIcons.RunConfigurations.TestPassed
                "red" -> AllIcons.RunConfigurations.TestError
                "yellow" -> AllIcons.RunConfigurations.TestFailed
                "aborted" -> AllIcons.RunConfigurations.TestTerminated
                "disabled" -> AllIcons.RunConfigurations.TestIgnored
                "notbuilt", "nobuilt" -> AllIcons.RunConfigurations.TestNotRan
                else -> AllIcons.RunConfigurations.TestUnknown
            }
        }

        private fun resultIcon(result: String?, building: Boolean, animated: Boolean): Icon = when {
            building -> if (animated) AnimatedIcon.Default.INSTANCE else AllIcons.Actions.Execute
            result == "SUCCESS" -> AllIcons.RunConfigurations.TestPassed
            result == "FAILURE" -> AllIcons.RunConfigurations.TestError
            result == "UNSTABLE" -> AllIcons.RunConfigurations.TestFailed
            result == "ABORTED" -> AllIcons.RunConfigurations.TestTerminated
            result == "NOT_BUILT" -> AllIcons.RunConfigurations.TestNotRan
            else -> AllIcons.RunConfigurations.TestUnknown
        }
    }
}
