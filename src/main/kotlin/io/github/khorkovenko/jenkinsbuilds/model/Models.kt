package io.github.khorkovenko.jenkinsbuilds.model

/** One build as it appears in a build list (and as the "last build" of a job). */
data class BuildRef(
    val number: Int,
    val displayName: String?,
    val result: String?,
    val building: Boolean,
    val timestamp: Long,
    val duration: Long,
    val estimatedDuration: Long,
    val startedBy: String?,
    /** The login of the user that started the build; [startedBy] is the name shown, which may be the full name. */
    val startedById: String? = null,
) {
    /** `C0.0.202` when the job names its builds, otherwise `#202`. */
    val title: String get() = displayName?.takeIf { it.isNotBlank() } ?: "#$number"
}

/** A job, a folder or a multibranch project. [fullName] is the Jenkins path `folder/sub/job`. */
data class JobNode(
    val name: String,
    val fullName: String,
    val displayName: String?,
    val color: String?,
    val jobClass: String?,
    val lastBuild: BuildRef?,
    val children: List<JobNode>,
    val container: Boolean,
) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: name
    val running: Boolean get() = color?.endsWith("_anime") == true
}

data class ViewSection(val name: String, val jobs: List<JobNode>)

data class ViewNode(
    val name: String,
    val jobs: List<JobNode>,
    val sections: List<ViewSection>,
    val views: List<ViewNode>,
)

/** Everything the job tree shows: all jobs of the server and the views that group them. */
data class ServerTree(val jobs: List<JobNode>, val views: List<ViewNode>, val primaryView: String?)

enum class ParamKind { STRING, TEXT, BOOLEAN, CHOICE, PASSWORD, LIST }

/**
 * A build parameter of a job. [CHOICE][ParamKind.CHOICE] has its values in [choices];
 * [LIST][ParamKind.LIST] is a parameter whose values the server computes (Git Parameter and similar), [choices]
 * holds them only when the server already sent them.
 */
data class ParamDef(
    val name: String,
    val kind: ParamKind,
    val description: String?,
    val defaultValue: String?,
    val choices: List<String>,
    val className: String?,
)

data class QueueItem(
    val id: Long,
    val why: String?,
    val cancelled: Boolean,
    val executableNumber: Int?,
)

data class JobDetails(
    val name: String,
    val fullName: String,
    val displayName: String?,
    val description: String?,
    val color: String?,
    val buildable: Boolean,
    val queueItem: QueueItem?,
    val params: List<ParamDef>,
    val builds: List<BuildRef>,
)

data class Artifact(val fileName: String, val relativePath: String)

data class ChangeEntry(
    val commitId: String?,
    val message: String,
    val author: String?,
    val timestamp: Long,
    val paths: List<String>,
)

data class GitInfo(val sha1: String?, val branches: List<String>, val remoteUrls: List<String>)

data class TestSummary(val total: Int, val failed: Int, val skipped: Int)

data class BuildDetails(
    val number: Int,
    val displayName: String?,
    val fullDisplayName: String?,
    val description: String?,
    val result: String?,
    val building: Boolean,
    val timestamp: Long,
    val duration: Long,
    val estimatedDuration: Long,
    val builtOn: String?,
    val causes: List<String>,
    val parameters: List<Pair<String, String>>,
    val artifacts: List<Artifact>,
    val git: List<GitInfo>,
    val changes: List<ChangeEntry>,
    val tests: TestSummary?,
    val culprits: List<String>,
) {
    val title: String get() = displayName?.takeIf { it.isNotBlank() } ?: "#$number"
}

data class Crumb(val field: String, val value: String)

data class WhoAmI(val name: String?, val anonymous: Boolean, val authenticated: Boolean)

/** A piece of console log: [nextStart] is the offset to ask for next, [more] is true while the build still writes. */
data class ConsoleChunk(val text: String, val nextStart: Long, val more: Boolean)
