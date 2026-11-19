package io.github.khorkovenko.jenkinsbuilds.auto

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsUrls
import java.util.concurrent.TimeUnit
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsParser
import io.github.khorkovenko.jenkinsbuilds.settings.JenkinsSettings

/**
 * "When this branch is pushed, build that job". [params] holds the build parameters as `name=value`, in the order
 * they are sent; [parameterized] says whether the job takes parameters at all. [lastLocal] and [lastRemote] are the
 * commits the branch and its remote counterpart were last seen at: a push is told from them, see [AutoBuildLogic].
 */
class AutoBuildRule {
    @JvmField var branch: String = ""
    @JvmField var root: String = ""
    @JvmField var job: String = ""
    @JvmField var parameterized: Boolean = false
    @JvmField var params: MutableList<String> = ArrayList()
    @JvmField var enabled: Boolean = true
    @JvmField var lastLocal: String = ""
    @JvmField var lastRemote: String = ""

    /** A shell command to run on the push; empty for none. */
    @JvmField var command: String = ""

    /** False: the command runs as soon as the push is seen. True: it runs when the build this rule started has finished. */
    @JvmField var commandAfterBuild: Boolean = false

    /** True: the command is not run at all, although its text is kept ("Not applied"). */
    @JvmField var commandOff: Boolean = false

    /** True: the command is typed into the Terminal tool window. False: it runs as a process shown in the Run tool window. */
    @JvmField var commandInTerminal: Boolean = true

    /**
     * Which terminal tab gets the command: empty for the selected one, [AutoBuildTerminal.NEW_TAB] for a new one
     * every time, otherwise the name of a tab. A named tab that is not open gets nothing: the command is not run.
     */
    @JvmField var commandTab: String = ""

    /**
     * Where the command runs: an id of [AutoBuildTargets] (a local shell, `ssh:` and the id of an SSH configuration,
     * or the Run window). Empty in rules saved before this could be chosen, see [AutoBuildTargets.effective].
     */
    @JvmField var commandTarget: String = ""

    /** The name the place had when it was chosen, shown when it is not available any more. */
    @JvmField var commandTargetName: String = ""

    /** After a successful build of this rule: comment a Jira issue and, when an assignee is set, reassign it. */
    @JvmField var jiraEnabled: Boolean = false

    /** The key of the issue; empty to take it from the branch name (`cp/2.2/CP-372` gives `CP-372`). */
    @JvmField var jiraIssue: String = ""
    @JvmField var jiraAssigneeId: String = ""
    @JvmField var jiraAssigneeName: String = ""
    @JvmField var jiraAssigneeCloud: Boolean = false
    @JvmField var jiraComment: String = ""

    fun parameters(): List<Pair<String, String>> = params.map { it.substringBefore('=') to it.substringAfter('=', "") }

    fun copy(): AutoBuildRule = AutoBuildRule().also {
        it.branch = branch
        it.root = root
        it.job = job
        it.parameterized = parameterized
        it.params = ArrayList(params)
        it.enabled = enabled
        it.lastLocal = lastLocal
        it.lastRemote = lastRemote
        it.command = command
        it.commandAfterBuild = commandAfterBuild
        it.commandOff = commandOff
        it.commandInTerminal = commandInTerminal
        it.commandTab = commandTab
        it.commandTarget = commandTarget
        it.commandTargetName = commandTargetName
        it.jiraEnabled = jiraEnabled
        it.jiraIssue = jiraIssue
        it.jiraAssigneeId = jiraAssigneeId
        it.jiraAssigneeName = jiraAssigneeName
        it.jiraAssigneeCloud = jiraAssigneeCloud
        it.jiraComment = jiraComment
    }

    /** `feature/x  →  customersportal-custom (Branch_Selector=origin/feature/x)` */
    fun describe(): String {
        val shown = parameters().filter { it.second.isNotEmpty() }.joinToString(", ") { "${it.first}=${it.second}" }
        return "$branch  →  $job" + if (shown.isEmpty()) "" else "  ($shown)"
    }
}

/** The auto-build rules of a project. They live in the workspace file: branches are a personal thing. */
@Service(Service.Level.PROJECT)
@State(name = "JenkinsBuildsAutoBuild", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AutoBuildSettings : PersistentStateComponent<AutoBuildSettings.State> {

    class State {
        @JvmField var enabled: Boolean = true
        @JvmField var rules: MutableList<AutoBuildRule> = ArrayList()
    }

    private var state = State()

    @Synchronized
    override fun getState(): State = state

    @Synchronized
    override fun loadState(state: State) {
        this.state = state
    }

    var enabled: Boolean
        @Synchronized get() = state.enabled
        @Synchronized set(value) {
            state.enabled = value
        }

    /** A copy of the rules, safe to show and edit. */
    @Synchronized
    fun rules(): List<AutoBuildRule> = state.rules.map { it.copy() }

    /**
     * Takes over [rules] as the dialog has them. Where a branch and its remote counterpart were last seen is kept
     * from the rule that is already stored: the dialog holds an older copy of it, and a push that happened while it
     * was open would otherwise be seen, and built, a second time.
     */
    @Synchronized
    fun replaceRules(rules: List<AutoBuildRule>) {
        val stored = state.rules.associateBy { Triple(it.branch, it.root, it.job) }
        state.rules = rules.map { rule ->
            rule.copy().also { copy ->
                stored[Triple(copy.branch, copy.root, copy.job)]?.let {
                    copy.lastLocal = it.lastLocal
                    copy.lastRemote = it.lastRemote
                }
            }
        }.toMutableList()
    }

    /** True when the feature is on and at least one rule is. */
    @Synchronized
    fun active(): Boolean = state.enabled && state.rules.any { it.enabled }

    /**
     * Records where a branch and its remote counterpart are now and returns the rules whose branch was just pushed.
     * [root] is the repository the branch is in.
     */
    @Synchronized
    fun observe(root: String, branch: String, local: String?, remote: String?): List<AutoBuildRule> {
        val pushed = ArrayList<AutoBuildRule>()
        for (rule in state.rules) {
            if (rule.branch != branch || (rule.root.isNotEmpty() && rule.root != root)) continue
            val fire = AutoBuildLogic.pushed(rule.lastLocal, rule.lastRemote, local, remote)
            rule.lastLocal = local ?: ""
            rule.lastRemote = remote ?: ""
            if (fire && state.enabled && rule.enabled) pushed += rule.copy()
        }
        return pushed
    }

    @Synchronized
    fun branchesWatched(root: String): List<String> =
        state.rules.filter { it.root.isEmpty() || it.root == root }.map { it.branch }.distinct()

    companion object {
        fun getInstance(project: Project): AutoBuildSettings = project.getService(AutoBuildSettings::class.java)
    }
}

/** Tells a push from the other things that move a branch. No IDE classes, so the tests can call it. */
object AutoBuildLogic {

    /**
     * True when the remote counterpart of a branch moved to the commit the local branch already was at: that is what
     * a push does. A fetch moves the remote branch away from the local one, a pull or a commit moves the local one,
     * and the first time a branch is seen ([lastLocal] and [lastRemote] both empty) nothing is known yet.
     */
    fun pushed(lastLocal: String, lastRemote: String, local: String?, remote: String?): Boolean {
        if (local.isNullOrEmpty() || remote.isNullOrEmpty()) return false
        if (lastLocal.isEmpty() && lastRemote.isEmpty()) return false
        return remote != lastRemote && remote == local && local == lastLocal
    }
}

/** What the plugin needs from Git. Implemented only when the Git plugin is there, so ask with [of]. */
interface AutoBuildGit {
    /** Local branches of the project's repositories, the checked-out ones first. */
    fun branches(): List<String>

    fun currentBranch(): String?

    /** The repository a branch is in, as a path. */
    fun rootOf(branch: String): String?

    /** `origin/feature/x` for `feature/x`: the name of its remote counterpart, guessed when it has none yet. */
    fun remoteName(branch: String): String

    /** The commits a branch and its remote counterpart are at now; null where there is none. */
    fun hashes(root: String, branch: String): Pair<String?, String?>

    companion object {
        fun of(project: Project): AutoBuildGit? = project.getService(AutoBuildGit::class.java)
    }
}

/** Told when an auto build was put into the Jenkins queue, so the tool window can follow it. */
fun interface AutoBuildListener {
    fun queued(job: String, queueId: Long?)

    companion object {
        @JvmField
        val TOPIC: Topic<AutoBuildListener> = Topic.create("Jenkins Builds auto build", AutoBuildListener::class.java)
    }
}

object AutoBuildRunner {

    private const val QUEUE_POLL_SECONDS = 3L
    private const val BUILD_POLL_SECONDS = 5L

    /** How long a build may wait in the queue, and then run, before its command is given up: 1 hour and 12 hours. */
    private const val MAX_QUEUE_POLLS = 1200
    private const val MAX_BUILD_POLLS = 8640

    private fun notify(project: Project, title: String, text: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance().getNotificationGroup("Jenkins Builds").createNotification(title, text, type).notify(project)
        }, ModalityState.any())
    }

    /**
     * Starts the build of [rule] in the background and reports the outcome in a notification. The command of the
     * rule runs at once, or after the build has finished, as the rule says.
     */
    fun trigger(project: Project, rule: AutoBuildRule) {
        // "Not applied": the command is kept in the rule but is not run, and no console opens for it
        val command = if (rule.commandOff) "" else rule.command.trim()
        ApplicationManager.getApplication().executeOnPooledThread {
            if (command.isNotEmpty() && !rule.commandAfterBuild) {
                AutoBuildCommand.run(project, rule, AutoBuildCommand.environment(rule, null, null))
            }
            try {
                val id = JenkinsSettings.getInstance().client()
                    .triggerBuild(rule.job, if (rule.parameterized) rule.parameters() else null)
                ApplicationManager.getApplication().invokeLater({
                    if (project.isDisposed) return@invokeLater
                    project.messageBus.syncPublisher(AutoBuildListener.TOPIC).queued(rule.job, id)
                }, ModalityState.any())
                notify(project, "Jenkins auto build", "${rule.branch} was pushed: ${rule.job} is queued", NotificationType.INFORMATION)
                if ((command.isNotEmpty() && rule.commandAfterBuild) || rule.jiraEnabled) {
                    if (id != null) {
                        awaitStart(project, rule, id, 0)
                    } else {
                        notify(
                            project, "Jenkins auto build",
                            "The server did not say where it queued ${rule.job}, so the build cannot be followed: what was to happen after it (the command, the Jira step) was skipped.",
                            NotificationType.WARNING,
                        )
                    }
                }
            } catch (e: Exception) {
                val reason = JenkinsParser.htmlToText(e.message ?: e.javaClass.simpleName, 400)
                val skipped = if (command.isNotEmpty() && rule.commandAfterBuild) " The command was not run." else ""
                notify(
                    project, "Jenkins auto build failed",
                    "${rule.branch} was pushed, but ${rule.job} was not started. $reason$skipped", NotificationType.ERROR,
                )
            }
        }
    }

    private fun later(seconds: Long, work: () -> Unit) {
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ work() }, seconds, TimeUnit.SECONDS)
    }

    /** Waits for the queue item to become a build, then for the build to end. A failed request is simply tried again. */
    private fun awaitStart(project: Project, rule: AutoBuildRule, queueId: Long, polls: Int) {
        later(QUEUE_POLL_SECONDS) {
            if (project.isDisposed) return@later
            val item = try {
                JenkinsSettings.getInstance().client().queueItem(queueId)
            } catch (_: Exception) {
                if (polls < MAX_QUEUE_POLLS) awaitStart(project, rule, queueId, polls + 1)
                return@later
            }
            val number = item?.executableNumber
            when {
                number != null -> awaitEnd(project, rule, number, 0)
                item == null || item.cancelled -> notify(
                    project, "Jenkins auto build", "The queued build of ${rule.job} was cancelled: the command was not run.", NotificationType.WARNING,
                )
                polls >= MAX_QUEUE_POLLS -> notify(
                    project, "Jenkins auto build", "${rule.job} is still waiting in the queue: the command was not run.", NotificationType.WARNING,
                )
                else -> awaitStart(project, rule, queueId, polls + 1)
            }
        }
    }

    private fun awaitEnd(project: Project, rule: AutoBuildRule, number: Int, polls: Int) {
        later(BUILD_POLL_SECONDS) {
            if (project.isDisposed) return@later
            val build = try {
                JenkinsSettings.getInstance().client().loadBuild(rule.job, number)
            } catch (_: Exception) {
                if (polls < MAX_BUILD_POLLS) awaitEnd(project, rule, number, polls + 1)
                return@later
            }
            when {
                !build.building -> {
                    if (rule.commandAfterBuild && !rule.commandOff && rule.command.isNotBlank()) {
                        AutoBuildCommand.run(project, rule, AutoBuildCommand.environment(rule, number, build.result))
                    }
                    if (rule.jiraEnabled) AutoBuildJira.afterBuild(project, rule, number, build.result)
                }
                polls >= MAX_BUILD_POLLS -> notify(
                    project, "Jenkins auto build", "${rule.job} #$number is still running: the command was not run.", NotificationType.WARNING,
                )
                else -> awaitEnd(project, rule, number, polls + 1)
            }
        }
    }
}

/** Runs the command of a rule in a console of the Run tool window. */
object AutoBuildCommand {

    /** How [command] is handed to the shell of the system: `cmd.exe /c` on Windows, `/bin/sh -c` elsewhere. */
    fun shellCommand(command: String, windows: Boolean): List<String> =
        if (windows) listOf("cmd.exe", "/c", command.trim()) else listOf("/bin/sh", "-c", command.trim())

    /**
     * What the command is told about the push and, after the build, about the build: `JENKINS_JOB`, `JENKINS_BRANCH`,
     * `JENKINS_URL`, and once the build has ended `JENKINS_BUILD_NUMBER`, `JENKINS_BUILD_RESULT` (`SUCCESS`,
     * `FAILURE`, ...) and `JENKINS_BUILD_URL`.
     */
    fun environment(rule: AutoBuildRule, number: Int?, result: String?, base: String = JenkinsSettings.getInstance().url): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["JENKINS_JOB"] = rule.job
        env["JENKINS_BRANCH"] = rule.branch
        env["JENKINS_URL"] = base
        if (number != null) {
            env["JENKINS_BUILD_NUMBER"] = number.toString()
            env["JENKINS_BUILD_RESULT"] = result ?: ""
            env["JENKINS_BUILD_URL"] = JenkinsUrls.buildUrl(base, rule.job, number)
        }
        return env
    }

    /**
     * Puts the values of [environment] into [command] wherever it names one of them as `$NAME`, `${NAME}` or
     * `%NAME%`. A command typed into the terminal runs in a shell that is already open, where no variable can be
     * set for it, so the values are written into the text instead, whatever shell it is.
     */
    fun substitute(command: String, environment: Map<String, String>): String {
        var text = command
        // Longest names first, so JENKINS_BUILD_URL is not cut short by JENKINS_BUILD
        for ((name, value) in environment.entries.sortedByDescending { it.key.length }) {
            text = text.replace("\${$name}", value).replace("%$name%", value)
            text = Regex("\\$" + Regex.escape(name) + "(?![A-Za-z0-9_])").replace(text) { value }
        }
        return text
    }

    /**
     * Runs the command of [rule]: typed into the Terminal tool window, or as a process of its own whose output opens
     * in the Run tool window, as the rule says.
     */
    fun run(project: Project, rule: AutoBuildRule, environment: Map<String, String>) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val target = AutoBuildTargets.effective(rule)
            if (target != AutoBuildTargets.RUN) {
                // A terminal or an SSH session: the command is typed into a new tab, with the values written into it
                val text = substitute(rule.command.trim(), environment)
                val problem = try {
                    if (target.startsWith(AutoBuildTargets.SSH_PREFIX)) {
                        val ssh = AutoBuildSsh.of(project)
                        if (ssh == null) "SSH sessions need the SSH plugin of the IDE, which is not enabled: the command was not run."
                        else ssh.run(target.removePrefix(AutoBuildTargets.SSH_PREFIX), text)
                    } else {
                        val shell = AutoBuildTargets.shellCommand(target)
                        if (shell == null && target == AutoBuildTargets.GIT_BASH) "Git Bash was not found on this machine: the command was not run."
                        else AutoBuildTerminal.runInNewTab(project, "Jenkins: ${rule.job}", shell, rule.root.ifEmpty { project.basePath }, text)
                    }
                } catch (e: Throwable) {
                    "The command could not be sent to the terminal: ${e.message ?: e.javaClass.simpleName}"
                }
                if (problem != null) {
                    NotificationGroupManager.getInstance().getNotificationGroup("Jenkins Builds")
                        .createNotification("Jenkins auto build", problem, NotificationType.ERROR).notify(project)
                }
                return@invokeLater
            }
            val title = "Jenkins: ${rule.job}"
            try {
                val folder = rule.root.ifEmpty { project.basePath ?: "" }
                val line = GeneralCommandLine(shellCommand(rule.command, SystemInfo.isWindows))
                    .withEnvironment(environment)
                    .withCharset(Charsets.UTF_8)
                if (folder.isNotEmpty()) line.withWorkDirectory(folder)
                val process = ColoredProcessHandler(line)
                val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
                console.attachToProcess(process)
                val descriptor = RunContentDescriptor(console, process, console.component, title)
                RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
                process.startNotify()
            } catch (e: Exception) {
                NotificationGroupManager.getInstance().getNotificationGroup("Jenkins Builds")
                    .createNotification("Jenkins auto build", "The command could not be started: ${e.message ?: e.javaClass.simpleName}", NotificationType.ERROR)
                    .notify(project)
            }
        }, ModalityState.any())
    }
}
