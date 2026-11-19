package io.github.khorkovenko.jenkinsbuilds.auto

import com.intellij.openapi.project.Project
import git4idea.GitLocalBranch
import git4idea.GitRemoteBranch
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager

/** The Git side of the auto build. Loaded only when the Git plugin is enabled (see `jenkinsBuilds-git.xml`). */
class AutoBuildGitImpl(private val project: Project) : AutoBuildGit {

    private fun repositories(): List<GitRepository> = GitRepositoryManager.getInstance(project).repositories

    override fun branches(): List<String> {
        val repositories = repositories()
        val current = repositories.mapNotNull { it.currentBranch?.name }
        val others = repositories.flatMap { r -> r.branches.localBranches.map { it.name } }.sortedBy { it.lowercase() }
        return (current + others).distinct()
    }

    override fun currentBranch(): String? = repositories().firstNotNullOfOrNull { it.currentBranch?.name }

    private fun repositoryOf(branch: String): GitRepository? {
        val repositories = repositories()
        return repositories.firstOrNull { it.currentBranch?.name == branch }
            ?: repositories.firstOrNull { it.branches.findLocalBranch(branch) != null }
    }

    override fun rootOf(branch: String): String? = repositoryOf(branch)?.root?.path

    override fun remoteName(branch: String): String {
        val repository = repositoryOf(branch)
        val local = repository?.branches?.findLocalBranch(branch)
        if (repository != null && local != null) remoteOf(repository, local)?.let { return it.name }
        val remote = repository?.remotes?.firstOrNull { it.name == "origin" } ?: repository?.remotes?.firstOrNull()
        return (remote?.name ?: "origin") + "/" + branch
    }

    override fun hashes(root: String, branch: String): Pair<String?, String?> {
        val repository = repositories().firstOrNull { it.root.path == root } ?: return null to null
        return hashes(repository, branch)
    }

    companion object {
        /** The branch a push of [local] goes to: the tracked one, or the remote branch of the same name. */
        fun remoteOf(repository: GitRepository, local: GitLocalBranch): GitRemoteBranch? =
            local.findTrackedBranch(repository)
                ?: repository.branches.remoteBranches.firstOrNull { it.nameForRemoteOperations == local.name }

        fun hashes(repository: GitRepository, branch: String): Pair<String?, String?> {
            val branches = repository.branches
            val local = branches.findLocalBranch(branch) ?: return null to null
            val remote = remoteOf(repository, local)
            return branches.getHash(local)?.asString() to remote?.let { branches.getHash(it)?.asString() }
        }
    }
}

/**
 * Hears every change of a Git repository (a commit, a fetch, a push, from the IDE or from a terminal) and starts the
 * builds of the branches that were pushed.
 */
class AutoBuildRepoListener(private val project: Project) : GitRepositoryChangeListener {

    override fun repositoryChanged(repository: GitRepository) {
        if (project.isDisposed) return
        val settings = AutoBuildSettings.getInstance(project)
        val root = repository.root.path
        for (branch in settings.branchesWatched(root)) {
            val (local, remote) = AutoBuildGitImpl.hashes(repository, branch)
            for (rule in settings.observe(root, branch, local, remote)) AutoBuildRunner.trigger(project, rule)
        }
    }
}
