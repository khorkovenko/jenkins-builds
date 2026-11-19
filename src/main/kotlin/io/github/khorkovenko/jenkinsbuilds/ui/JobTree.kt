package io.github.khorkovenko.jenkinsbuilds.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import io.github.khorkovenko.jenkinsbuilds.model.JobNode
import io.github.khorkovenko.jenkinsbuilds.model.ServerTree
import io.github.khorkovenko.jenkinsbuilds.model.ViewNode
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsSettings
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * The job tree as the tool window and the Auto dialog both show it: the favourites first, then every job of the
 * server with its folders, then the views with their sections.
 */
object JobTree {

    const val FAVOURITES = "Favourites"
    const val FAVOURITES_KEY = "favourites"

    /** Marks the star in a job row, so a click on it can be told from a click on the name. */
    val STAR_TAG = Any()

    private val STAR = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0xD9A300, 0xE5C04B))

    const val RUNNING = "In Progress"
    const val RUNNING_KEY = "running"

    /**
     * A view, a section of a view, the "all jobs" group, or one of the two groups the plugin adds itself: the
     * favourites and the jobs that are building now. Those two carry their key in [special].
     */
    class Group(val name: String, val view: Boolean, val special: String? = null) {
        val favourites: Boolean get() = special == FAVOURITES_KEY
        val running: Boolean get() = special == RUNNING_KEY
        override fun toString(): String = name
    }

    /**
     * What [fill] put into the tree: the node with every job, how many groups follow the plugin's own ones, and
     * whether there is a group of running jobs.
     */
    class Filled(val allNode: DefaultMutableTreeNode?, val groups: Int, val hasRunning: Boolean)

    /** Every buildable job of the server by its full name, those only a view lists included. */
    fun jobs(tree: ServerTree): Map<String, JobNode> {
        val known = LinkedHashMap<String, JobNode>()
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
        return known
    }

    /**
     * Puts the groups of [tree] under [root] (which is emptied first), leaving out what does not match [query].
     * With [onlyFavourites] only the favourites are shown. The folders in [hidden] (by their [key]) are left out
     * unless [showHidden] is set.
     */
    fun fill(
        root: DefaultMutableTreeNode, tree: ServerTree, query: String, onlyFavourites: Boolean, favourites: List<String>,
        hidden: Collection<String> = emptyList(), showHidden: Boolean = false,
    ): Filled {
        root.removeAllChildren()
        // The favourites come first, as a flat list in the order they were added
        val known = jobs(tree)
        addGroup(root, Group(FAVOURITES, true, FAVOURITES_KEY), favourites.mapNotNull { known[it] }, query)
        if (onlyFavourites) {
            if (!showHidden) prune(root, hidden.toSet())
            return Filled(null, 0, false)
        }

        // Then what is building right now, wherever it lives
        val afterFavourites = root.childCount
        addGroup(root, Group(RUNNING, true, RUNNING_KEY), known.values.filter { it.running }.sortedBy { it.fullName.lowercase() }, query)
        val hasRunning = root.childCount > afterFavourites

        val before = root.childCount
        addGroup(root, Group(tree.primaryView?.takeIf { it.isNotBlank() } ?: "All", true), tree.jobs, query)
        val allNode = if (root.childCount > before) root.getChildAt(before) as DefaultMutableTreeNode else null
        for (view in tree.views) {
            // The primary view lists every job again: the group above already shows them, with their folders
            val isAll = view.name.equals(tree.primaryView, ignoreCase = true) || view.name.equals("all", ignoreCase = true)
            if (isAll && view.sections.isEmpty() && view.views.isEmpty()) continue
            addView(root, view, query)
        }
        if (!showHidden) prune(root, hidden.toSet())
        return Filled(allNode, root.childCount - before, (0 until root.childCount).any { ((root.getChildAt(it) as DefaultMutableTreeNode).userObject as? Group)?.running == true })
    }

    /** True for what can be hidden: a view, a section, one of the plugin's own groups or a Jenkins folder. */
    fun isFolder(node: Any?): Boolean = when (val o = (node as? DefaultMutableTreeNode)?.userObject) {
        is Group -> true
        is JobNode -> o.container
        else -> false
    }

    /** Takes the folders whose [key] is in [hidden] out of the tree, with everything in them. */
    private fun prune(node: DefaultMutableTreeNode, hidden: Set<String>) {
        if (hidden.isEmpty()) return
        for (i in node.childCount - 1 downTo 0) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            if (isFolder(child) && key(TreePath(child.path)) in hidden) node.remove(i) else prune(child, hidden)
        }
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

    /** A text that names a node by its place in the tree; it survives a rebuild of the tree. */
    fun key(path: TreePath): String = path.path.drop(1).joinToString("\u0000") { node ->
        when (val o = (node as DefaultMutableTreeNode).userObject) {
            is JobNode -> "j:" + o.fullName
            // The group of favourites has a key of its own, so a Jenkins view of the same name does not share it
            is Group -> o.special ?: ("g:" + o.name)
            else -> o.toString()
        }
    }

    /** The job of a node; folders and groups give null. */
    fun jobOf(node: Any?): JobNode? = ((node as? DefaultMutableTreeNode)?.userObject as? JobNode)?.takeUnless { it.container }

    /** The first node of [fullName] below [root], the favourites first. */
    fun findJob(root: DefaultMutableTreeNode, fullName: String): DefaultMutableTreeNode? {
        val nodes = root.preorderEnumeration()
        while (nodes.hasMoreElements()) {
            val node = nodes.nextElement() as DefaultMutableTreeNode
            if (jobOf(node)?.fullName == fullName) return node
        }
        return null
    }

    /** The icon for a Jenkins ball colour: `blue`, `red`, `yellow`, `aborted`, `disabled`, `notbuilt`, each also as `_anime`. */
    fun colorIcon(color: String?): Icon {
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

    /**
     * Draws groups, folders and jobs. With [clickableStar] every job has a star to click (☆ or ★); without it only
     * the favourites show their ★.
     */
    class Renderer(private val clickableStar: Boolean) : ColoredTreeCellRenderer() {
        /** A hidden folder is only drawn while the hidden ones are shown: then it is greyed and says so. */
        private fun hidden(node: DefaultMutableTreeNode): Boolean {
            val settings = JenkinsSettings.getInstance()
            return settings.hiddenFolders.isNotEmpty() && node.parent != null && settings.isHiddenFolder(key(TreePath(node.path)))
        }

        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is Group -> {
                    icon = when {
                        item.running -> AnimatedIcon.Default.INSTANCE
                        item.favourites -> AllIcons.Nodes.Favorite
                        item.view -> AllIcons.Nodes.Folder
                        else -> AllIcons.Nodes.Package
                    }
                    if (hidden(value)) {
                        append(item.name, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                        append("  hidden", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    } else {
                        append(item.name, if (item.view) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                }
                is JobNode -> {
                    if (item.container) {
                        icon = AllIcons.Nodes.Folder
                        if (hidden(value)) {
                            append(item.label, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                            append("  hidden", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        } else {
                            append(item.label)
                        }
                    } else {
                        icon = colorIcon(item.color)
                        append(item.label)
                        val favourite = JenkinsSettings.getInstance().isFavouriteJob(item.fullName)
                        when {
                            favourite -> append("  ★ ", STAR, STAR_TAG)
                            clickableStar -> append("  ☆ ", SimpleTextAttributes.GRAYED_ATTRIBUTES, STAR_TAG)
                            else -> append("  ")
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
}
