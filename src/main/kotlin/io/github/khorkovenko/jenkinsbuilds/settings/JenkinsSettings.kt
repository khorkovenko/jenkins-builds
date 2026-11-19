package io.github.khorkovenko.jenkinsbuilds.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic
import io.github.khorkovenko.jenkinsbuilds.api.ConnectionConfig
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsClient
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsException
import io.github.khorkovenko.jenkinsbuilds.api.JenkinsUrls
import io.github.khorkovenko.jenkinsbuilds.jira.JiraClient
import io.github.khorkovenko.jenkinsbuilds.jira.JiraConfig
import io.github.khorkovenko.jenkinsbuilds.jira.JiraException
import io.github.khorkovenko.jenkinsbuilds.jira.JiraUrls

/** Told when the connection settings were applied. */
fun interface JenkinsSettingsListener {
    fun settingsChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<JenkinsSettingsListener> =
            Topic.create("Jenkins Builds settings", JenkinsSettingsListener::class.java)
    }
}

/**
 * The connection settings. Address, user name and the numbers are saved in `jenkinsBuilds.xml` in the IDE configuration;
 * the password or API token is kept in the IDE password store ([PasswordSafe]) and never written to that file.
 */
@Service(Service.Level.APP)
@State(name = "JenkinsBuildsSettings", storages = [Storage("jenkinsBuilds.xml", roamingType = RoamingType.DISABLED)])
class JenkinsSettings : PersistentStateComponent<JenkinsSettings.State> {

    class State {
        @JvmField var url: String = ""
        @JvmField var username: String = ""
        @JvmField var timeoutSeconds: Int = 30
        @JvmField var buildsLimit: Int = 30
        @JvmField var refreshSeconds: Int = 5

        /** Full names of the jobs marked as favourite. */
        @JvmField var favouriteJobs: MutableList<String> = ArrayList()

        /** Values of list parameters (branches such as `origin/cp/2.2/dev`) marked as favourite. */
        @JvmField var favouriteValues: MutableList<String> = ArrayList()
        @JvmField var onlyFavouriteJobs: Boolean = false
        @JvmField var onlyFavouriteValues: Boolean = false

        /** The folders, views and sections hidden in the job tree, by their place in it. */
        @JvmField var hiddenFolders: MutableList<String> = ArrayList()
        @JvmField var showHiddenFolders: Boolean = false

        // Jira: the address and the user are saved here, the password or token in the password store
        @JvmField var jiraUrl: String = ""
        @JvmField var jiraUsername: String = ""

        /** The text of the filter field of the Jira tab; kept only while the field is locked. */
        @JvmField var jiraFilter: String = ""
        @JvmField var jiraFilterLocked: Boolean = false

        /** Divider positions of the tool window: side by side (docked at the bottom) and stacked (docked left or right). */
        @JvmField var jobsSplitHorizontal: Float = 0.24f
        @JvmField var buildsSplitHorizontal: Float = 0.42f
        @JvmField var jobsSplitVertical: Float = 0.34f
        @JvmField var buildsSplitVertical: Float = 0.38f
    }

    private var state = State()

    @Volatile private var cachedSecret: String? = null
    @Volatile private var client: JenkinsClient? = null

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
        client = null
    }

    val url: String get() = JenkinsUrls.normalizeBase(state.url)
    val username: String get() = state.username.trim()
    val timeoutSeconds: Int get() = state.timeoutSeconds.coerceIn(1, 600)
    val buildsLimit: Int get() = state.buildsLimit.coerceIn(1, 500)
    val refreshSeconds: Int get() = state.refreshSeconds.coerceIn(2, 600)
    val configured: Boolean get() = url.isNotEmpty()

    // ---- favourites (UI thread) -------------------------------------------------------------------------------------

    val favouriteJobs: List<String> get() = state.favouriteJobs

    fun isFavouriteJob(fullName: String): Boolean = fullName in state.favouriteJobs

    fun setFavouriteJob(fullName: String, favourite: Boolean) = mark(state.favouriteJobs, fullName, favourite)

    var onlyFavouriteJobs: Boolean
        get() = state.onlyFavouriteJobs
        set(value) {
            state.onlyFavouriteJobs = value
        }

    val favouriteValues: List<String> get() = state.favouriteValues

    fun isFavouriteValue(value: String): Boolean = value in state.favouriteValues

    fun setFavouriteValue(value: String, favourite: Boolean) = mark(state.favouriteValues, value, favourite)

    var onlyFavouriteValues: Boolean
        get() = state.onlyFavouriteValues
        set(value) {
            state.onlyFavouriteValues = value
        }

    val hiddenFolders: List<String> get() = state.hiddenFolders

    fun isHiddenFolder(key: String): Boolean = key in state.hiddenFolders

    fun setHiddenFolder(key: String, hidden: Boolean) = mark(state.hiddenFolders, key, hidden)

    var showHiddenFolders: Boolean
        get() = state.showHiddenFolders
        set(value) {
            state.showHiddenFolders = value
        }

    private fun mark(list: MutableList<String>, item: String, favourite: Boolean) {
        if (item.isBlank()) return
        if (favourite) {
            if (item !in list) list += item
        } else {
            list.remove(item)
        }
    }

    private fun attributes(): CredentialAttributes = CredentialAttributes(generateServiceName("Jenkins Builds", "server"))

    /** The saved password or token, empty when there is none. Reads the password store: not for the UI thread. */
    fun loadSecret(): String {
        cachedSecret?.let { return it }
        val secret = PasswordSafe.instance.getPassword(attributes()) ?: ""
        cachedSecret = secret
        return secret
    }

    /** True when the IDE is set to forget passwords on exit (Settings | Appearance & Behavior | System Settings | Passwords). */
    fun secretsAreMemoryOnly(): Boolean = PasswordSafe.instance.isMemoryOnly

    /** Saves everything. Writes the password store: not for the UI thread. */
    fun save(url: String, username: String, secret: String, timeoutSeconds: Int, buildsLimit: Int, refreshSeconds: Int) {
        state.url = JenkinsUrls.normalizeBase(url)
        state.username = username.trim()
        state.timeoutSeconds = timeoutSeconds
        state.buildsLimit = buildsLimit
        state.refreshSeconds = refreshSeconds
        val credentials = if (secret.isEmpty()) null else Credentials(state.username, secret)
        PasswordSafe.instance.set(attributes(), credentials)
        cachedSecret = secret
        client = null
        ApplicationManager.getApplication().messageBus.syncPublisher(JenkinsSettingsListener.TOPIC).settingsChanged()
    }

    /**
     * The client for the saved connection. It is kept between calls so the CSRF crumb and the session are reused.
     * Reads the password store on first use: not for the UI thread.
     */
    @Synchronized
    fun client(): JenkinsClient {
        client?.let { return it }
        if (!configured) throw JenkinsException("The Jenkins server is not set up yet. Open the settings and enter the address and the login.")
        val created = JenkinsClient(ConnectionConfig(url, username, loadSecret(), timeoutSeconds))
        client = created
        return created
    }

    /**
     * Drops the client and the password read from the store, so the next call reads the store again. Used after the
     * server refused the login: the store may have been locked when the password was read.
     */
    fun forgetLogin() {
        cachedSecret = null
        client = null
    }

    // ---- Jira -------------------------------------------------------------------------------------------------------

    @Volatile private var cachedJiraSecret: String? = null
    @Volatile private var jira: JiraClient? = null

    val jiraUrl: String get() = JiraUrls.normalizeBase(state.jiraUrl)
    val jiraUsername: String get() = state.jiraUsername.trim()
    val jiraConfigured: Boolean get() = jiraUrl.isNotEmpty()

    private fun jiraAttributes(): CredentialAttributes = CredentialAttributes(generateServiceName("Jenkins Builds", "jira"))

    /** The saved Jira password or token, empty when there is none. Reads the password store: not for the UI thread. */
    fun loadJiraSecret(): String {
        cachedJiraSecret?.let { return it }
        val secret = PasswordSafe.instance.getPassword(jiraAttributes()) ?: ""
        cachedJiraSecret = secret
        return secret
    }

    /** Saves the Jira login. Writes the password store: not for the UI thread. */
    fun saveJira(url: String, username: String, secret: String) {
        state.jiraUrl = JiraUrls.normalizeBase(url)
        state.jiraUsername = username.trim()
        PasswordSafe.instance.set(jiraAttributes(), if (secret.isEmpty()) null else Credentials(state.jiraUsername, secret))
        cachedJiraSecret = secret
        jira = null
        ApplicationManager.getApplication().messageBus.syncPublisher(JenkinsSettingsListener.TOPIC).settingsChanged()
    }

    /** The client for the saved Jira login. Reads the password store on first use: not for the UI thread. */
    @Synchronized
    fun jiraClient(): JiraClient {
        jira?.let { return it }
        if (!jiraConfigured) throw JiraException("Jira is not set up yet. Open the settings and enter the address and the login on the Jira tab.")
        val created = JiraClient(JiraConfig(jiraUrl, jiraUsername, loadJiraSecret(), timeoutSeconds))
        jira = created
        return created
    }

    fun forgetJiraLogin() {
        cachedJiraSecret = null
        jira = null
    }

    companion object {
        fun getInstance(): JenkinsSettings = ApplicationManager.getApplication().getService(JenkinsSettings::class.java)
    }
}
