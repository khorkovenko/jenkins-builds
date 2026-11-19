package io.github.khorkovenko.jenkinsbuilds.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.table.JBTable
import javax.swing.DefaultCellEditor
import javax.swing.SwingConstants
import java.awt.Point
import java.awt.event.MouseAdapter
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import javax.swing.Action
import javax.swing.JComboBox
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsParser
import io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildGit
import io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildRule
import io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildSettings
import io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildJira
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.DoubleClickListener
import java.awt.event.MouseEvent
import io.github.khorkovenko.jenkinsbuilds.auto.AutoBuildTargets
import io.github.khorkovenko.jenkinsbuilds.model.JobDetails
import io.github.khorkovenko.jenkinsbuilds.model.ParamDef
import io.github.khorkovenko.jenkinsbuilds.model.ParamKind
import io.github.khorkovenko.jenkinsbuilds.model.ServerTree
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsSettings
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * The Auto dialog: the list of "when this branch is pushed, build that job" rules of the project, each with a
 * checkbox, and one checkbox that switches the whole feature. Nothing is saved before OK.
 *
 * [tree] is the job tree the tool window shows (null when it is not loaded), [preferredJob] the job selected there.
 */
class AutoBuildDialog(
    private val project: Project,
    private val tree: ServerTree?,
    private val preferredJob: String?,
    private val server: Server = LiveServer,
) : DialogWrapper(project) {

    companion object {
        const val ENABLED = 0
        const val RULE = 1
        const val COMMAND = 2
        const val WHEN = 3
        const val WHERE = 4
        const val JIRA = 5

        // When the command runs
        const val IMMEDIATELY = "Immediately"
        const val AFTER_BUILD = "After the build has finished"
        const val NOT_APPLIED = "Not applied"

        private const val COMMAND_HELP = "<html>In the terminal: typed into your own shell, exactly as you would type it.<br>" +
            "In the Run window: run through cmd.exe /c on Windows and /bin/sh -c elsewhere.<br>" +
            "The names JENKINS_JOB, JENKINS_BRANCH and JENKINS_URL, written as \$NAME or %NAME%, are filled in,<br>" +
            "and after the build also JENKINS_BUILD_NUMBER, JENKINS_BUILD_RESULT and JENKINS_BUILD_URL.</html>"
    }

    /** What the add form asks the Jenkins server. The tests put their own answers here. */
    interface Server {
        /** Runs [work] away from the UI thread and hands its result to [done] on the UI thread. */
        fun <T> run(work: () -> T, done: (Result<T>) -> Unit)

        fun job(fullName: String): JobDetails

        /** The values the server offers for a branch selector of a job. */
        fun values(fullName: String, definition: ParamDef): List<String>
    }

    private object LiveServer : Server {
        override fun <T> run(work: () -> T, done: (Result<T>) -> Unit) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = runCatching(work)
                ApplicationManager.getApplication().invokeLater({ done(result) }, ModalityState.any())
            }
        }

        override fun job(fullName: String): JobDetails = JenkinsSettings.getInstance().client().loadJob(fullName, 1)

        override fun values(fullName: String, definition: ParamDef): List<String> =
            JenkinsSettings.getInstance().client().parameterValues(fullName, definition)
    }

    private val settings = AutoBuildSettings.getInstance(project)
    private val git = AutoBuildGit.of(project)
    private val rules = settings.rules().toMutableList()

    /** Where a command can run: the shells of this machine, the SSH configurations of the IDE, the Run window. */
    private val targets = AutoBuildTargets.all(project)
    private val enabledBox = JBCheckBox("Build automatically when one of these branches is pushed", settings.enabled)
    private val model = RulesModel()
    private val table = JBTable(model)

    init {
        title = "Auto Build on Push"
        init()
    }

    override fun getDimensionServiceKey(): String = "io.github.khorkovenko.jenkinsbuilds.AutoBuildDialog6"

    /** The rules as they are in the dialog now; the tests look at them. */
    internal fun currentRules(): List<AutoBuildRule> = rules.toList()

    internal fun ruleTable(): JBTable = table

    /**
     * The rules as a table: a checkbox, what is built when which branch is pushed, a command to run on the push and
     * when to run it. The command and its time are edited in place; an empty command runs nothing.
     */
    private inner class RulesModel : AbstractTableModel() {
        private val columns = arrayOf("", "When pushed  →  build", "Command", "Run the command", "In", "Jira after a successful build")

        override fun getRowCount(): Int = rules.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getColumnClass(column: Int): Class<*> = if (column == ENABLED) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int): Boolean = column != RULE && column != JIRA && column != WHERE

        override fun getValueAt(row: Int, column: Int): Any {
            val rule = rules[row]
            return when (column) {
                ENABLED -> rule.enabled
                RULE -> rule.describe()
                COMMAND -> rule.command
                WHEN -> when {
                    rule.commandOff -> NOT_APPLIED
                    rule.commandAfterBuild -> AFTER_BUILD
                    else -> IMMEDIATELY
                }
                WHERE -> AutoBuildTargets.label(rule, targets)
                else -> AutoBuildJira.describe(rule)
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val rule = rules.getOrNull(row) ?: return
            when (column) {
                ENABLED -> rule.enabled = value == true
                COMMAND -> rule.command = (value as? String).orEmpty().trim()
                WHEN -> {
                    rule.commandOff = value == NOT_APPLIED
                    rule.commandAfterBuild = value == AFTER_BUILD
                }
                WHERE -> targets.firstOrNull { it.label == value }?.let {
                    rule.commandTarget = it.id
                    rule.commandTargetName = it.label.removePrefix(AutoBuildTargets.SSH_LABEL)
                    rule.commandInTerminal = it.id != AutoBuildTargets.RUN
                }
            }
            fireTableCellUpdated(row, column)
            save()
        }
    }

    override fun createCenterPanel(): JComponent {
        table.emptyText.text = "No branches yet. Press + to add one."
        enabledBox.addActionListener { save() }
        table.setShowGrid(false)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        // What is typed into a cell is kept when the focus goes elsewhere, for example to the OK button
        table.putClientProperty("terminateEditOnFocusLost", true)
        table.columnModel.getColumn(ENABLED).apply {
            maxWidth = JBUI.scale(36)
            minWidth = JBUI.scale(36)
        }
        table.columnModel.getColumn(RULE).preferredWidth = JBUI.scale(360)
        table.columnModel.getColumn(COMMAND).apply {
            preferredWidth = JBUI.scale(280)
            val field = JBTextField()
            field.emptyText.text = "No command"
            cellEditor = DefaultCellEditor(field).apply { clickCountToStart = 1 }
            cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int) =
                    super.getTableCellRendererComponent(t, value, selected, focus, row, column).also {
                        toolTipText = COMMAND_HELP
                    }
            }
        }
        table.columnModel.getColumn(WHEN).apply {
            preferredWidth = JBUI.scale(190)
            cellEditor = DefaultCellEditor(JComboBox(arrayOf(IMMEDIATELY, AFTER_BUILD, NOT_APPLIED))).apply { clickCountToStart = 1 }
        }
        table.columnModel.getColumn(JIRA).preferredWidth = JBUI.scale(260)
        table.columnModel.getColumn(JIRA).cellRenderer = fullTextRenderer()
        table.columnModel.getColumn(RULE).cellRenderer = fullTextRenderer()
        // The Jira step has several fields: it is edited in a dialog of its own, opened by a double click on its cell
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (table.columnAtPoint(event.point) != JIRA) return false
                editJira()
                return true
            }
        }.installOn(table)
        // One click on a cell of the "In" column opens the list of places
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1 || table.columnAtPoint(event.point) != WHERE) return
                chooseWhere(table.rowAtPoint(event.point))
            }
        })
        table.columnModel.getColumn(WHERE).apply {
            preferredWidth = JBUI.scale(320)
            cellRenderer = whereRenderer()
        }
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction { add() }
            .setRemoveAction { remove() }
            .addExtraAction(object : DumbAwareAction("Jira After the Build...", "After a successful build of the selected rule: comment a Jira issue and reassign it", AllIcons.Actions.Edit) {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = table.selectedRow in rules.indices
                }

                override fun actionPerformed(e: AnActionEvent) = editJira()
            })
            .disableUpDownActions()
            .createPanel()
        val hint = JBLabel(
            if (git == null) "The Git plugin is disabled: pushes cannot be seen, so nothing will be built automatically."
            else "<html>A push from the IDE or from a terminal is noticed while this project is open in the IDE.<br>" +
                "Click a Command cell to type or paste a command, or clear it to run nothing. \"In\" chooses where it goes: " +
                "a new terminal tab of one of your shells, a new SSH session of one of the SSH configurations of the IDE, " +
                "or the Run window. A new tab is opened every time. Changes are saved as you make them.</html>",
        ).apply {
            font = JBUI.Fonts.smallFont()
            foreground = if (git == null) UIUtil.getErrorForeground() else UIUtil.getContextHelpForeground()
            border = JBUI.Borders.emptyTop(6)
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(enabledBox, BorderLayout.NORTH)
            add(decorated, BorderLayout.CENTER)
            add(hint, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(1400), JBUI.scale(340))
        }
    }

    /** Opens the Jira step of the selected rule. */
    private fun editJira() {
        stopEditing()
        val index = table.selectedRow
        val rule = rules.getOrNull(index) ?: return
        val dialog = JiraActionDialog(project, rule)
        if (!dialog.showAndGet()) return
        dialog.applyTo(rule)
        model.fireTableRowsUpdated(index, index)
        save()
    }

    /**
     * The places of the "In" column are chosen in a popup list of the IDE instead of a drop-down inside the cell:
     * a drop-down there is only as wide as its column and cuts long names such as those of SSH configurations.
     * The popup is as wide as its longest entry, and typing in it searches.
     */
    private fun chooseWhere(row: Int) {
        if (row !in rules.indices) return
        stopEditing()
        table.selectionModel.setSelectionInterval(row, row)
        val labels = targets.map { it.label }
        val cell = table.getCellRect(row, WHERE, true)
        JBPopupFactory.getInstance().createPopupChooserBuilder(labels)
            .setTitle("Run the Command In")
            .setNamerForFiltering { it }
            .setSelectedValue(AutoBuildTargets.label(rules[row], targets), true)
            .setItemChosenCallback { chosen -> model.setValueAt(chosen, row, WHERE) }
            .createPopup()
            .show(RelativePoint(table, Point(cell.x, cell.y + cell.height)))
    }

    /** Draws a cell of the "In" column like a drop-down: the text and an arrow at its right end. */
    private fun whereRenderer() = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int) =
            super.getTableCellRendererComponent(t, value, selected, focus, row, column).also {
                toolTipText = value?.toString()
                icon = AllIcons.General.ArrowDown
                horizontalTextPosition = SwingConstants.LEFT
                iconTextGap = JBUI.scale(8)
            }
    }


    /** Shows the whole text of a cell as its tooltip, for texts the column is too narrow for. */
    private fun fullTextRenderer() = object : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(t: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int) =
            super.getTableCellRendererComponent(t, value, selected, focus, row, column).also { toolTipText = value?.toString()?.takeIf { it.isNotEmpty() } }
    }

    private fun stopEditing() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
    }

    private fun fill() {
        stopEditing()
        model.fireTableDataChanged()
    }

    private fun select(index: Int) {
        if (index in rules.indices) table.selectionModel.setSelectionInterval(index, index)
    }

    private fun remove() {
        stopEditing()
        val index = table.selectedRow
        if (index !in rules.indices) return
        rules.removeAt(index)
        fill()
        select(minOf(index, rules.lastIndex))
        save()
    }

    private fun add() {
        val form = RuleForm(project, git?.branches() ?: emptyList(), git?.currentBranch(), tree, preferredJob, server) { branch ->
            git?.remoteName(branch) ?: "origin/$branch"
        }
        if (!form.showAndGet()) return
        val rule = form.rule() ?: return
        // Where the branch is now: only a push after this moment starts a build
        rule.root = git?.rootOf(rule.branch) ?: ""
        if (git != null && rule.root.isNotEmpty()) {
            val (local, remote) = git.hashes(rule.root, rule.branch)
            rule.lastLocal = local ?: ""
            rule.lastRemote = remote ?: ""
        }
        rules += rule
        fill()
        select(rules.lastIndex)
        save()
    }

    /**
     * Writes the rules and the main switch into the settings. It is called after every change, so nothing is lost
     * when the window is closed with its cross or with Esc.
     */
    private fun save() {
        settings.enabled = enabledBox.isSelected
        settings.replaceRules(rules)
    }

    /** There is nothing to cancel: the only button closes the window. */
    override fun createActions(): Array<Action> = arrayOf(okAction.also { it.putValue(Action.NAME, "Close") })

    override fun doOKAction() {
        stopEditing()
        save()
        super.doOKAction()
    }

    /** The cross and Esc: what is being typed into a cell is kept too. */
    override fun doCancelAction() {
        stopEditing()
        save()
        super.doCancelAction()
    }

    /**
     * The form of a new rule.
     *
     * 1. The branch of the project whose push starts the build.
     * 2. The Jenkins job, chosen in the same tree as in the tool window: favourites, all jobs with their folders,
     *    views and sections, with a filter.
     * 3. Only for a job with a branch selector (a `-custom` job): the branch to build, a drop-down of the branches
     *    the server offers, the favourite ones first.
     *
     * Where there is nothing to choose from (no Git, the jobs not loaded, the branches not readable) a text field
     * takes the place of the list. [remoteName] gives `origin/x` for the branch `x`.
     */
    internal class RuleForm(
        project: Project,
        private val branches: List<String>,
        currentBranch: String?,
        private val tree: ServerTree?,
        preferredJob: String?,
        private val server: Server,
        private val remoteName: (String) -> String,
    ) : DialogWrapper(project) {

        private val branchBox: ComboBox<String>? = branches.takeIf { it.isNotEmpty() }?.let { ComboBox(it.toTypedArray()) }
        private val branchField = JBTextField()

        private val jobFilter = SearchTextField(false)
        private val jobRoot = DefaultMutableTreeNode("root")
        private val jobModel = DefaultTreeModel(jobRoot)
        private val jobTree = Tree(jobModel)
        private val jobField = JBTextField()
        private var fillingTree = false

        private val valueLabel = JBLabel("Branch to build:")
        private val valueBox = ComboBox<String>()
        private val valueField = JBTextField()
        private val valuePanel = JPanel(BorderLayout())
        private val statusLabel = JBLabel(" ")

        /** The job the lower part of the form was last filled for; null before the first time. */
        private var shownJob: String? = null
        private var details: JobDetails? = null

        /** The branch selectors of the job; all of them get the chosen branch. */
        private var selectors: List<ParamDef> = emptyList()
        private var loading = false
        private var generation = 0
        private var adjusting = false

        /** True once the user chose a branch to build himself: then it no longer follows the first field. */
        private var picked = false

        init {
            title = "Add Auto Build"
            branchBox?.let { box ->
                box.isSwingPopup = false // the IDE popup has speed search
                box.selectedItem = currentBranch?.takeIf { it in branches } ?: branches.first()
                box.addActionListener { follow() }
            }
            branchField.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = follow()
            })

            jobTree.isRootVisible = false
            jobTree.showsRootHandles = true
            jobTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            jobTree.cellRenderer = JobTree.Renderer(clickableStar = false)
            jobTree.emptyText.text = "No job matches"
            jobTree.addTreeSelectionListener { if (!fillingTree) jobChanged() }
            jobFilter.textEditor.emptyText.text = "Filter jobs"
            jobFilter.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = fillJobs(job())
            })
            jobField.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = jobChanged()
            })

            valueBox.isSwingPopup = false
            valueBox.addActionListener { if (!adjusting) picked = true }
            valueField.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) {
                    if (!adjusting) picked = true
                }
            })
            valueLabel.isVisible = false
            valuePanel.isVisible = false
            statusLabel.font = JBUI.Fonts.smallFont()

            init()
            fillJobs(preferredJob)
        }

        // ---- the job tree -----------------------------------------------------------------------------------------

        /** Fills the tree for the filter and selects [select] when it is still shown. */
        private fun fillJobs(select: String?) {
            val jobs = tree
            if (jobs != null) {
                val query = jobFilter.text.trim()
                fillingTree = true
                try {
                    val settings = JenkinsSettings.getInstance()
                    JobTree.fill(jobRoot, jobs, query, false, settings.favouriteJobs, settings.hiddenFolders, settings.showHiddenFolders)
                    jobModel.reload()
                    for (i in 0 until jobRoot.childCount) {
                        val node = jobRoot.getChildAt(i) as DefaultMutableTreeNode
                        // The favourites are always open; with a filter everything that is left is
                        if (query.isNotEmpty()) {
                            expandAll(node)
                        } else if ((node.userObject as? JobTree.Group)?.favourites == true) {
                            jobTree.expandPath(TreePath(node.path))
                        }
                    }
                    val node = select?.takeIf { it.isNotEmpty() }?.let { JobTree.findJob(jobRoot, it) }
                    if (node != null) {
                        val path = TreePath(node.path)
                        jobTree.selectionPath = path
                        jobTree.scrollPathToVisible(path)
                    }
                } finally {
                    fillingTree = false
                }
            }
            jobChanged()
        }

        private fun expandAll(node: DefaultMutableTreeNode) {
            if (node.isLeaf) return
            jobTree.expandPath(TreePath(node.path))
            for (i in 0 until node.childCount) expandAll(node.getChildAt(i) as DefaultMutableTreeNode)
        }

        // ---- what the job needs -------------------------------------------------------------------------------------

        private fun status(text: String, error: Boolean = false) {
            statusLabel.foreground = if (error) JBColor.RED else UIUtil.getContextHelpForeground()
            statusLabel.text = text.ifEmpty { " " }
        }

        private fun showValueRow(component: JComponent?) {
            valuePanel.removeAll()
            if (component != null) valuePanel.add(component, BorderLayout.CENTER)
            valueLabel.isVisible = component != null
            valuePanel.isVisible = component != null
            valuePanel.revalidate()
            valuePanel.repaint()
        }

        /** Reads the chosen job and, when it has a branch selector, the branches it can build. */
        private fun jobChanged() {
            val job = job()
            if (job == shownJob) return
            shownJob = job
            details = null
            selectors = emptyList()
            picked = false
            showValueRow(null)
            val mine = ++generation
            if (job.isEmpty()) {
                loading = false
                status("Choose the job to build.")
                isOKActionEnabled = false
                return
            }
            loading = true
            isOKActionEnabled = false
            status("Reading $job ...")
            server.run({ server.job(job) }) { result ->
                if (mine != generation || isDisposed) return@run
                result.onFailure {
                    loading = false
                    status(JenkinsParser.htmlToText(it.message ?: it.javaClass.simpleName, 300), error = true)
                }.onSuccess { read ->
                    details = read
                    selectors = read.params.filter { it.kind == ParamKind.LIST }
                    val selector = selectors.firstOrNull()
                    when {
                        selector == null -> ready(
                            if (read.params.isEmpty()) "$job builds without parameters."
                            else "$job has no branch selector: it is built with the defaults of its parameters.",
                        )
                        selector.choices.isNotEmpty() -> offer(selector.choices, selector)
                        else -> {
                            status("Reading the branches $job can build ...")
                            server.run({ server.values(job, selector) }) { values ->
                                if (mine != generation || isDisposed) return@run
                                offer(values.getOrDefault(emptyList()), selector)
                            }
                        }
                    }
                }
            }
        }

        private fun ready(text: String) {
            loading = false
            isOKActionEnabled = true
            status(text)
        }

        /** Shows the branches to build: a drop-down of [values], or a text field when there are none to list. */
        private fun offer(values: List<String>, selector: ParamDef) {
            val favourites = JenkinsSettings.getInstance().favouriteValues.filter { it in values }
            val ordered = favourites + values.filter { it !in favourites }
            adjusting = true
            try {
                if (ordered.isEmpty()) {
                    valueField.text = remoteName(branch())
                    showValueRow(valueField)
                } else {
                    valueBox.model = DefaultComboBoxModel(ordered.toTypedArray())
                    valueBox.selectedItem = preferred(ordered, selector)
                    showValueRow(valueBox)
                }
            } finally {
                adjusting = false
            }
            ready(
                if (ordered.isEmpty()) "The server did not list the branches: type the branch to build."
                else "${ordered.size} branches. ${selector.name} is set to the chosen one for every auto build.",
            )
        }

        /** The remote name of the project branch when the job can build it, else the default of the job. */
        private fun preferred(values: List<String>, selector: ParamDef): String {
            val wanted = remoteName(branch())
            val default = selector.defaultValue
            return when {
                wanted in values -> wanted
                default != null && default in values -> default
                else -> values.first()
            }
        }

        /** The project branch changed: the branch to build follows it until the user chose one himself. */
        private fun follow() {
            if (picked || !valuePanel.isVisible) return
            val selector = selectors.firstOrNull() ?: return
            adjusting = true
            try {
                if (valueBox.parent === valuePanel) {
                    val values = (0 until valueBox.itemCount).map { valueBox.getItemAt(it) }
                    if (values.isNotEmpty()) valueBox.selectedItem = preferred(values, selector)
                } else {
                    valueField.text = remoteName(branch())
                }
            } finally {
                adjusting = false
            }
        }

        // ---- answers ------------------------------------------------------------------------------------------------

        fun branch(): String = (branchBox?.let { it.selectedItem as? String } ?: branchField.text).orEmpty().trim()

        fun job(): String =
            if (tree != null) JobTree.jobOf(jobTree.selectionPath?.lastPathComponent)?.fullName.orEmpty() else jobField.text.trim()

        /** The branch the job is to build; empty when the job has no branch selector. */
        fun buildBranch(): String = when {
            !valuePanel.isVisible -> ""
            valueBox.parent === valuePanel -> (valueBox.selectedItem as? String).orEmpty().trim()
            else -> valueField.text.trim()
        }

        /** The branches offered in the third drop-down, in their order. */
        internal fun offeredBranches(): List<String> =
            if (valuePanel.isVisible && valueBox.parent === valuePanel) (0 until valueBox.itemCount).map { valueBox.getItemAt(it) } else emptyList()

        internal fun isReady(): Boolean = !loading && details != null

        internal fun statusText(): String = statusLabel.text

        internal fun selectBranch(branch: String) {
            if (branchBox != null) branchBox.selectedItem = branch else branchField.text = branch
        }

        internal fun selectJob(fullName: String) {
            if (tree == null) {
                jobField.text = fullName
            } else {
                JobTree.findJob(jobRoot, fullName)?.let { jobTree.selectionPath = TreePath(it.path) }
            }
        }

        internal fun selectBuildBranch(value: String) {
            if (valueBox.parent === valuePanel) valueBox.selectedItem = value else valueField.text = value
        }

        internal fun filterJobs(text: String) {
            jobFilter.text = text
        }

        internal fun jobGroups(): List<String> = (0 until jobRoot.childCount).map { jobRoot.getChildAt(it).toString() }

        /** The rule the form describes, or null while the job is not read. */
        fun rule(): AutoBuildRule? {
            val read = details ?: return null
            val rule = AutoBuildRule()
            rule.branch = branch()
            rule.job = read.fullName.ifBlank { job() }
            rule.parameterized = read.params.isNotEmpty()
            val value = buildBranch()
            if (value.isNotEmpty()) rule.params = selectors.map { "${it.name}=$value" }.toMutableList()
            return rule
        }

        override fun getPreferredFocusedComponent(): JComponent = branchBox ?: branchField

        override fun getDimensionServiceKey(): String = "io.github.khorkovenko.jenkinsbuilds.AutoBuildRuleForm2"

        override fun createCenterPanel(): JComponent {
            // A drop-down keeps its own width in a form: the wrapper lets it use the whole row
            val branch = JPanel(BorderLayout()).apply { add(branchBox ?: branchField, BorderLayout.CENTER) }
            val top = FormBuilder.createFormBuilder().addLabeledComponent(JBLabel("When this branch is pushed:"), branch)
            val bottom = FormBuilder.createFormBuilder()
                .addLabeledComponent(valueLabel, valuePanel)
                .addComponent(statusLabel)
                .panel
            if (tree == null) {
                jobField.emptyText.text = "The jobs are not loaded: type the full name of the job"
                top.addLabeledComponent(JBLabel("Build this Jenkins job:"), jobField)
                return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                    add(top.panel, BorderLayout.NORTH)
                    add(bottom, BorderLayout.CENTER)
                    preferredSize = Dimension(JBUI.scale(640), preferredSize.height)
                }
            }
            top.addComponent(JBLabel("Build this Jenkins job:"), JBUI.scale(8))
            // The tree is in the middle of a border layout: it takes all the height that is left and gives a little
            // of it back when the row of the branch to build appears, instead of being squeezed to one line
            val scroll = ScrollPaneFactory.createScrollPane(jobTree)
            scroll.minimumSize = Dimension(JBUI.scale(200), JBUI.scale(160))
            val jobs = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                add(jobFilter, BorderLayout.NORTH)
                add(scroll, BorderLayout.CENTER)
            }
            return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                add(top.panel, BorderLayout.NORTH)
                add(jobs, BorderLayout.CENTER)
                add(bottom, BorderLayout.SOUTH)
                preferredSize = Dimension(JBUI.scale(720), JBUI.scale(520))
                minimumSize = Dimension(JBUI.scale(480), JBUI.scale(360))
            }
        }

        override fun doValidate(): ValidationInfo? = when {
            branch().isEmpty() -> ValidationInfo("Choose the branch", branchBox ?: branchField)
            job().isEmpty() -> ValidationInfo("Choose the job", if (tree == null) jobField else jobTree)
            loading -> ValidationInfo("The job is still being read")
            details == null -> ValidationInfo("The job could not be read")
            valuePanel.isVisible && buildBranch().isEmpty() -> ValidationInfo("Choose the branch to build", valuePanel)
            else -> null
        }
    }
}
