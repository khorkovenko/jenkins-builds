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
import com.intellij.ui.LayeredIcon
import javax.swing.SwingConstants
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsException
import io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildListener
import io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildSettings
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
    private val autoBadge: Icon = IconLoader.getIcon("/icons/autoBadge.svg", JenkinsPanel::class.java)
    private val autoOnIcon: Icon = IconLoader.getIcon("/icons/autoOn.svg", JenkinsPanel::class.java)
    private val autoOffIcon: Icon = IconLoader.getIcon("/icons/autoOff.svg", JenkinsPanel::class.java)
    private val clearFavouritesIcon: Icon = IconLoader.getIcon("/icons/clearFavourites.svg", JenkinsPanel::class.java)

    // Declared before the init block: the selection listeners set them while the first tree is filled
    /** The selected job and build, for the menus, which are filled on a background thread. */
    @Volatile private var menuJob: String? = null
    @Volatile private var menuBuild: Pair<String, Int>? = null

    /** Answers read for the menus: job lists for a short while, finished builds for good. */
    private val menuCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Any>>()

    /** What the button of the tool window shows now: the colour of the status dot and whether the auto build is on. */
    private var shownBadge: String? = null

    /** Whether the tree had a group of running jobs the last time it was filled. */
    private var runningShown = false

    /** Set when a build of mine started or ended, so the job tree is loaded again at the next tick. */
    private var treeIsStale = false

    /** A build to select as soon as it shows up in the list (a queue item that started). */
    private var selectWhenListed: Int? = null

    /** Address and user of the server whose data is shown, see [serverIdentity]. */
    private var shownServer: String? = null

    private var lastPoll = 0L
    private var consoleSession = 0

    /** Failed attempts in a row to read the next piece of the log. */
    private var consoleFailures = 0
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

        // A build started by a push: follow it like one started with the Build button
        project.messageBus.connect(this).subscribe(AutoBuildListener.TOPIC, AutoBuildListener { job, id ->
            if (disposed) return@AutoBuildListener
            if (id != null) tracked[id] = Tracked(job, "Waiting in the queue")
            lastPoll = 0
            updateBadge()
            status("$job: queued by the auto build")
            if (currentJob?.fullName == job) {
                fillRows()
                reloadJob(quiet = true)
            }
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(JenkinsSettingsListener.TOPIC, JenkinsSettingsListener {
            ApplicationManager.getApplication().invokeLater({ if (!disposed) refreshAll() }, ModalityState.any())
        })
        poller = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            ApplicationManager.getApplication().invokeLater({ if (!disposed) tick() }, ModalityState.any())
        }, 2, 1, TimeUnit.SECONDS)

        refreshAll()
        updateBadge()
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
            add(action("Remove All Favourites", "Unstar every favourite job", clearFavouritesIcon,
                { JenkinsSettings.getInstance().favouriteJobs.isNotEmpty() }) { clearFavourites() })
            add(toggle("Show Hidden Folders", "Show the folders you have hidden, greyed, so they can be brought back",
                AllIcons.Actions.ToggleVisibility, { true }, { JenkinsSettings.getInstance().showHiddenFolders }) {
                JenkinsSettings.getInstance().showHiddenFolders = it
                rebuildTree()
                val count = JenkinsSettings.getInstance().hiddenFolders.size
                status(if (it) "Showing the $count hidden folder" + (if (count == 1) "" else "s") else "Hidden folders are not shown")
            })
            add(action("Expand All", "Open every view and folder", AllIcons.Actions.Expandall, { treeRoot.childCount > 0 }) {
                var row = 0
                while (row < jobTree.rowCount) jobTree.expandRow(row++)
            })
            add(action("Collapse All", "Close every view and folder", AllIcons.Actions.Collapseall, { treeRoot.childCount > 0 }) {
                for (row in jobTree.rowCount - 1 downTo 0) jobTree.collapseRow(row)
            })
            add(Separator.getInstance())
            // Shown pressed while the auto build is on; a click always opens its dialog
            // A solid orange button while the auto build is on, a grey outline while it is off
            add(object : DumbAwareAction("Auto", "Auto build on push: choose a branch of this project and the job to build when it is pushed", autoOffIcon) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    val on = !disposed && AutoBuildSettings.getInstance(project).active()
                    e.presentation.icon = if (on) autoOnIcon else autoOffIcon
                    e.presentation.text = if (on) "Auto Build on Push Is On" else "Auto Build on Push Is Off"
                    e.presentation.isEnabled = !disposed
                }

                override fun actionPerformed(e: AnActionEvent) {
                    if (!disposed) openAutoBuild()
                }
            })
            add(action("Jenkins Settings", "Server address and login", AllIcons.General.Settings, { true }) { openSettings() })
        }
        // First block: the artifact links of the job's last (successful) build, mine or anybody's
        val jobPopup = DefaultActionGroup().apply {
            Pick.entries.forEach { add(createPickGroup(it)) }
            add(Separator.getInstance())
            add(action("Open in Browser", "Open the selected job in the browser", AllIcons.General.Web, { selectedTreeJob() != null }) {
                val base = JenkinsSettings.getInstance().url
                selectedTreeJob()?.let { if (base.isNotEmpty()) BrowserUtil.browse(JenkinsUrls.jobUrl(base, it.fullName)) }
            })
            add(favouriteAction)
            add(createHideAction())
        }
        PopupHandler.installPopupMenu(jobTree, jobPopup, "JenkinsBuilds.JobPopup")
        PopupHandler.installPopupMenu(buildsTable, DefaultActionGroup(createRowLinksGroup()), "JenkinsBuilds.BuildPopup")
        jobTree.addTreeSelectionListener { menuJob = selectedTreeJob()?.fullName }
        // A right click picks the build under the mouse first, so its menu is about that build
        buildsTable.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!javax.swing.SwingUtilities.isRightMouseButton(e)) return
                val row = buildsTable.rowAtPoint(e.point)
                if (row >= 0 && !buildsTable.isRowSelected(row)) buildsTable.selectionModel.setSelectionInterval(row, row)
            }
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("JenkinsBuilds.Toolbar", group, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        // Jobs
        jobTree.isRootVisible = false
        jobTree.showsRootHandles = true
        jobTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        jobTree.cellRenderer = JobTree.Renderer(clickableStar = true)
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
            menuBuild = (selectedRow() as? Row.Build)?.let { row -> currentJob?.let { job -> job.fullName to row.ref.number } }
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
        return if (cell.getFragmentTagAt(e.x - bounds.x) === JobTree.STAR_TAG) job else null
    }

    private fun setFavourite(job: JobNode, favourite: Boolean) {
        JenkinsSettings.getInstance().setFavouriteJob(job.fullName, favourite)
        if (favourite) expanded += JobTree.FAVOURITES_KEY
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

    private fun clearFavourites() {
        val settings = JenkinsSettings.getInstance()
        val favourites = settings.favouriteJobs.toList()
        if (favourites.isEmpty()) return
        val what = if (favourites.size == 1) "'${favourites[0]}'" else "all ${favourites.size} favourite jobs"
        if (Messages.showYesNoDialog(project, "Remove $what from the favourites? The jobs themselves stay.",
                "Jenkins Builds", Messages.getQuestionIcon()) != Messages.YES) return
        favourites.forEach { settings.setFavouriteJob(it, false) }
        rebuildTree()
        status("Removed ${favourites.size} job" + (if (favourites.size == 1) "" else "s") + " from the favourites")
    }

    // ---- artifact links in the menus ----------------------------------------------------------------------------------
    // The menus are filled on a background thread (ActionUpdateThread.BGT), so they read the server there and never
    // touch Swing: the selected job and build are mirrored into these fields by the selection listeners.

    /** Which build of a job a menu of the job tree is about. */
    private enum class Pick(val label: String, val mine: Boolean, val successful: Boolean) {
        MY_LAST("My Last Build", true, false),
        MY_SUCCESSFUL("My Last Successful Build", true, true),
        LAST("Last Build", false, false),
        SUCCESSFUL("Last Successful Build", false, true),
    }

    private fun menuJobDetails(client: JenkinsClient, fullName: String): JobDetails {
        val key = "job\n$fullName"
        menuCache[key]?.let { (time, value) -> if (System.currentTimeMillis() - time < MENU_CACHE_MILLIS) return value as JobDetails }
        return client.loadJob(fullName, JenkinsSettings.getInstance().buildsLimit).also { menuCache[key] = System.currentTimeMillis() to it }
    }

    private fun menuBuildDetails(client: JenkinsClient, fullName: String, number: Int): BuildDetails {
        val key = "build\n$fullName\n$number"
        menuCache[key]?.let { (_, value) -> return value as BuildDetails }
        val details = client.loadBuild(fullName, number)
        if (!details.building) menuCache[key] = System.currentTimeMillis() to details // a running build may still add artifacts
        return details
    }

    /** The finished build [pick] means among [builds] (newest first), or null. */
    private fun choose(builds: List<BuildRef>, pick: Pick, me: String): BuildRef? = builds.firstOrNull { b ->
        !b.building &&
            (!pick.mine || (b.startedById ?: b.startedBy).equals(me, ignoreCase = true) || b.startedBy.equals(me, ignoreCase = true)) &&
            (!pick.successful || b.result == "SUCCESS")
    }

    /** A grey line in a menu that only tells something. */
    private fun info(text: String): AnAction = object : DumbAwareAction(text) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = false }
        override fun actionPerformed(e: AnActionEvent) {}
    }

    private fun copyAction(text: String, description: String, value: String, icon: Icon? = null): AnAction =
        object : DumbAwareAction(text, description, icon) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
            override fun actionPerformed(e: AnActionEvent) { if (!disposed) copy(value) }
        }

    /** "Copy Link: ROOT-C0.0.214-plain.war" for each artifact of the build, then all of them and the build page in the browser. */
    private fun linkActions(fullName: String, details: BuildDetails): List<AnAction> {
        val base = JenkinsSettings.getInstance().url
        val urls = details.artifacts.map { it to JenkinsUrls.artifactUrl(base, fullName, details.number, it.relativePath) }
        return buildList {
            if (urls.isEmpty()) add(info(if (details.building) "Still running: no artifacts yet" else "No artifacts"))
            urls.forEach { (artifact, url) -> add(copyAction("Copy Link: ${artifact.fileName}", url, url, AllIcons.Actions.Copy)) }
            if (urls.size > 1) add(copyAction("Copy All ${urls.size} Artifact Links", "One link per line", urls.joinToString("\n") { it.second }))
            add(Separator.getInstance())
            val buildUrl = JenkinsUrls.buildUrl(base, fullName, details.number)
            add(object : DumbAwareAction("Open in Browser", buildUrl, AllIcons.General.Web) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) { if (!disposed) BrowserUtil.browse(buildUrl) }
            })
        }
    }

    /** The line shown for a menu that could not read the server. */
    private fun failedInfo(e: Exception): AnAction {
        if (e is ProcessCanceledException) throw e
        return info("Could not load: " + message(e).take(80))
    }

    /** A submenu of the job tree with the artifact links of the build [pick] means for the selected job. */
    private fun createPickGroup(pick: Pick): AnAction = object : com.intellij.openapi.actionSystem.ActionGroup(pick.label, true) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            val job = if (disposed) null else menuJob
            e.presentation.isEnabledAndVisible = job != null
            if (job == null) return
            // The build beside the label once the job's builds are known (they are read when a submenu opens)
            val cached = menuCache["job\n$job"]?.second as? JobDetails
            val build = cached?.let { choose(it.builds, pick, JenkinsSettings.getInstance().username) }
            e.presentation.text = if (build != null) "${pick.label}  ·  ${build.title}" else pick.label
        }

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            if (e == null || disposed) return emptyArray()
            val job = menuJob ?: return emptyArray()
            val me = JenkinsSettings.getInstance().username
            if (pick.mine && me.isEmpty()) return arrayOf(info("Set your user name in the Jenkins settings first"))
            return try {
                val client = JenkinsSettings.getInstance().client()
                val builds = menuJobDetails(client, job).builds
                val build = choose(builds, pick, me)
                    ?: return arrayOf(info("No such build among the last ${builds.size} builds"))
                val details = menuBuildDetails(client, job, build.number)
                val started = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(details.timestamp))
                (listOf(info("${details.title}  #${details.number}  ·  ${details.result ?: "?"}  ·  $started"), Separator.getInstance()) +
                    linkActions(job, details)).toTypedArray()
            } catch (ex: Exception) {
                arrayOf(failedInfo(ex))
            }
        }
    }

    /** The menu of a row in the builds table: the links of that build's artifacts. */
    private fun createRowLinksGroup(): AnAction = object : com.intellij.openapi.actionSystem.ActionGroup() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            if (e == null || disposed) return emptyArray()
            val (job, number) = menuBuild ?: return arrayOf(info("Select a build"))
            return try {
                linkActions(job, menuBuildDetails(JenkinsSettings.getInstance().client(), job, number)).toTypedArray()
            } catch (ex: Exception) {
                arrayOf(failedInfo(ex))
            }
        }
    }

    /** The key of the folder, view or section the tree selection points at; jobs give null. */
    private fun selectedFolderKey(): String? {
        val path = jobTree.selectionPath ?: return null
        return if (JobTree.isFolder(path.lastPathComponent)) key(path) else null
    }

    /** Hides the folder with [key], or brings it back. */
    internal fun setFolderHidden(key: String, hidden: Boolean) {
        val settings = JenkinsSettings.getInstance()
        settings.setHiddenFolder(key, hidden)
        rebuildTree()
        status(
            when {
                !hidden -> "The folder is shown again"
                settings.showHiddenFolders -> "The folder is marked as hidden"
                else -> "The folder is hidden. Show Hidden Folders on the toolbar brings it back into view."
            },
        )
    }

    /** "Hide Folder" in the menu of the tree; on a hidden folder (seen with Show Hidden Folders) it is "Show Folder Again". */
    private fun createHideAction(): AnAction = object : DumbAwareAction("Hide Folder", "Hide this folder in the job tree", AllIcons.Actions.ToggleVisibility) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val key = if (disposed) null else selectedFolderKey()
            e.presentation.isEnabledAndVisible = key != null
            if (key != null) {
                e.presentation.text = if (JenkinsSettings.getInstance().isHiddenFolder(key)) "Show Folder Again" else "Hide Folder"
            }
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (disposed) return
            val key = selectedFolderKey() ?: return
            setFolderHidden(key, !JenkinsSettings.getInstance().isHiddenFolder(key))
        }
    }

    /** Shows [tree] as if the server had answered with it. For the tests. */
    internal fun showTree(tree: ServerTree) {
        server = tree
        rebuildTree()
    }

    private fun openAutoBuild() {
        if (AutoBuildDialog(project, server, currentJob?.fullName).showAndGet()) {
            val settings = AutoBuildSettings.getInstance(project)
            val count = settings.rules().count { it.enabled }
            status(if (settings.active()) "Auto build on push is on for $count branch" + (if (count == 1) "" else "es") else "Auto build on push is off")
            updateBadge()
        }
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
            // Everything is caught, a cancellation too: the slot must always be released on the UI thread below,
            // or it would count as busy for ever and its polling would stop
            val result = try {
                Result.success(work(JenkinsSettings.getInstance().client()))
            } catch (e: Throwable) {
                // The login was refused: the password may have been read while the store was locked, read it again next time
                if ((e as? JenkinsException)?.status == 401) JenkinsSettings.getInstance().forgetLogin()
                Result.failure(e)
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed || s.generation != generation) return@invokeLater
                s.future = null
                result.fold(done) { if (it !is ProcessCanceledException) failed(it) }
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

    private fun serverIdentity(): String = JenkinsSettings.getInstance().let { it.url + "\n" + it.username }

    private fun refreshAll() {
        // Another server or another user: nothing that is shown or followed belongs to it
        val identity = serverIdentity()
        if (identity != shownServer) {
            shownServer = identity
            for (slot in listOf("tree", "job", "queue", "watch")) cancel(slot)
            server = null
            currentJob = null
            jobDetails = null
            selectWhenListed = null
            tracked.clear()
            watched.clear()
            unseen.clear()
            updateBadge()
            clearBuild("Select a build to see its details.")
            rows.clear()
            buildsModel.fireTableDataChanged()
            buildsTable.emptyText.text = "Select a job"
        }
        loadTree(quiet = false)
        if (currentJob != null) reloadJob(quiet = false)
        if (currentBuild != null) reloadBuild(quiet = true)
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

    private fun key(path: TreePath): String = JobTree.key(path)

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
                val filled = JobTree.fill(
                    treeRoot, tree, query, onlyFavourites, settings.favouriteJobs, settings.hiddenFolders, settings.showHiddenFolders,
                )
                if (firstTree) expanded += JobTree.FAVOURITES_KEY
                // The group of running jobs opens by itself each time it appears
                if (filled.hasRunning && !runningShown) expanded += JobTree.RUNNING_KEY
                runningShown = filled.hasRunning
                if (!onlyFavourites && firstTree) {
                    firstTree = false
                    // Start with the views open; the group with everything only when there is no other view
                    for (i in 0 until treeRoot.childCount) {
                        val node = treeRoot.getChildAt(i) as DefaultMutableTreeNode
                        if (node === filled.allNode && filled.groups > 1) continue
                        remember(node, 2)
                    }
                }
            }
            treeModel.reload()
            if (tree != null && treeRoot.childCount == 0) {
                jobTree.emptyText.text = when {
                    onlyFavourites && settings.favouriteJobs.isEmpty() -> "No favourite jobs yet: click the star beside a job"
                    query.isNotEmpty() -> "No job matches \"$query\""
                    onlyFavourites -> "The favourite jobs are not on this server"
                    settings.hiddenFolders.isNotEmpty() -> "Every folder is hidden: press Show Hidden Folders on the toolbar"
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
        if (levels <= 0 || node.userObject !is JobTree.Group) return
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
                details.builds.filter { it.building && (it.startedById ?: it.startedBy).equals(me, ignoreCase = true) }
                    .forEach { watchBuild(job.fullName, it.number) }
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
            if (artifacts.isNotEmpty()) artifactsModel.fireTableRowsUpdated(0, artifacts.size - 1)
        }
    }

    private fun startConsole() {
        val job = currentJob ?: return
        val number = currentBuild ?: return
        val wanted = job.fullName to number
        if (consoleShown == wanted) return
        consoleShown = wanted
        console.clear()
        consoleFailures = 0
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
        fun again(from: Long, delayMillis: Long) {
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                ApplicationManager.getApplication().invokeLater({
                    if (!disposed && session == consoleSession) followConsole(fullName, number, from, session)
                }, ModalityState.any())
            }, delayMillis, TimeUnit.MILLISECONDS)
        }
        load("console", { it.console(fullName, number, start) }, failed = { error ->
            if (session != consoleSession) return@load
            // One lost request must not end the following of a running build: try the same piece again a few times
            if (++consoleFailures <= MAX_CONSOLE_RETRIES) {
                again(start, CONSOLE_POLL_MILLIS * 2)
            } else {
                console.print("\n" + message(error) + "\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
        }) { chunk ->
            if (session != consoleSession) return@load
            consoleFailures = 0
            printConsole(chunk.text, last = !chunk.more)
            if (chunk.more) {
                again(chunk.nextStart, CONSOLE_POLL_MILLIS)
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
        // One of my builds started or ended: the status icons and the In Progress group are out of date
        if (treeIsStale && !busy("tree")) {
            treeIsStale = false
            loadTree(quiet = true)
        }
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
                treeIsStale = true
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
        val auto = AutoBuildSettings.getInstance(project).active()
        val state = "${color?.rgb}/$auto"
        if (state == shownBadge) return
        shownBadge = state
        var icon: Icon = if (color == null) baseIcon else ExecutionUtil.getIndicator(baseIcon, baseIcon.iconWidth, baseIcon.iconHeight, color)
        if (auto) {
            // The auto build on push is on: a small lightning in the lower left corner
            icon = LayeredIcon(2).also {
                it.setIcon(icon, 0)
                it.setIcon(autoBadge, 1, SwingConstants.SOUTH_WEST)
            }
        }
        toolWindow.setIcon(icon)
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
                        treeIsStale = true
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

            private var cancelled = false

            override fun onCancel() {
                cancelled = true
            }

            override fun onFinished() {
                // Cancelled before anything was saved: there is nothing to tell
                if (cancelled && saved.isEmpty()) return
                val group = NotificationGroupManager.getInstance().getNotificationGroup("Jenkins Builds")
                val error = failure
                val text = when {
                    cancelled -> "Download cancelled: ${saved.size} of ${list.size} files saved to ${directory.path}"
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

    companion object {
        private const val ARTIFACTS_TAB = 1
        private const val CONSOLE_TAB = 2
        private const val CONSOLE_POLL_MILLIS = 1500L
        private const val MAX_SEARCH_MARKS = 2000
        private const val MAX_CONSOLE_RETRIES = 5
        private const val MENU_CACHE_MILLIS = 20_000L

        private fun consoleType(name: String, color: JBColor): ConsoleViewContentType =
            ConsoleViewContentType(name, TextAttributes(color, null, null, null, Font.PLAIN))

        private val CONSOLE_SUCCESS = consoleType("JENKINS_BUILDS_SUCCESS", JBColor(0x1F7A36, 0x5FB865))
        private val CONSOLE_WARNING = consoleType("JENKINS_BUILDS_WARNING", JBColor(0xA36B00, 0xE0A040))
        private val CONSOLE_STEP = consoleType("JENKINS_BUILDS_STEP", JBColor(0x2470B3, 0x6CA5E0))
        private const val MAX_SIZED_ARTIFACTS = 50

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
