package com.strmr.tv.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.api.Profile
import com.strmr.tv.app.AppSession
import com.strmr.tv.app.Config
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class KidsLiveChannel(val id: String, val name: String, val programmeState: String = "unknown",
    val programmeReason: String = "", val programme: KidsLiveProgramme? = null) {
    val selectable get() = id.isNotBlank() && programmeState == "within_limit" && programme != null
    val description get() = when (programmeState) {
        "within_limit" -> programme?.let { "${it.title}. ${it.classificationLabel}. Playback ends with this programme." }
        "too_old" -> "This programme is above the profile's age limit according to the selected guide."
        else -> "No clear age information in the selected guide."
    } ?: "No clear programme information in the selected guide."
    fun channel(): LiveChannel {
        check(selectable) { "Choose a permitted programme" }
        return LiveChannel(id, name, "")
    }
}

@Serializable
data class KidsLiveChannelsReply(val channels: List<KidsLiveChannel> = emptyList(), val total: Int = 0)

suspend fun ApiClient.kidsLiveChannels(expectedContext: Long): KidsLiveChannelsReply {
    val query = inContext(expectedContext) { mapOf("profileId" to profileId, "clientId" to Config.clientId) }
    val reply = send<KidsLiveChannelsReply>("GET", "/api/live/kids/channels", query, timeoutMs = 30_000, expectedContext = expectedContext)
    ProfileDataContext.requireCurrent(expectedContext)
    return reply
}

fun liveMenuVisible(kids: Boolean, configured: Boolean, normallyVisible: Boolean) = if (kids) configured else normallyVisible

/** Never persisted: changing profiles or waking the app requires a new policy read. */
@Composable
fun rememberKidsLiveAvailable(profile: Profile?): Boolean {
    val context by ProfileDataContext.changes.collectAsState()
    val wakes by AppSession.wakes.collectAsState()
    var available by remember(context, profile?.isKidsProfile) { mutableStateOf(false) }
    LaunchedEffect(context, wakes, profile?.isKidsProfile) {
        available = false
        if (profile?.isKidsProfile == true) {
            available = try { ApiClient.kidsLivePolicy(context).configured }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { false }
        }
    }
    return profile?.isKidsProfile == true && available
}

data class KidsLiveBrowseState(val channels: List<KidsLiveChannel> = emptyList(), val loading: Boolean = true, val error: String? = null)

class KidsLiveBrowser(
    val context: Long,
    private val readPolicy: suspend (Long) -> KidsLivePolicy = { ApiClient.kidsLivePolicy(it) },
    private val readChannels: suspend (Long) -> KidsLiveChannelsReply = { ApiClient.kidsLiveChannels(it) },
) {
    private val mutable = MutableStateFlow(KidsLiveBrowseState())
    val state: StateFlow<KidsLiveBrowseState> = mutable
    private var busy = false
    suspend fun load() {
        if (busy) return
        ProfileDataContext.requireCurrent(context)
        busy = true
        mutable.value = KidsLiveBrowseState()
        try {
            val policy = readPolicy(context)
            ProfileDataContext.requireCurrent(context)
            if (!policy.configured) throw ApiError.NotReady("Live TV is not enabled. A parent can configure it in the server's Kids Settings.")
            val channels = readChannels(context)
            ProfileDataContext.requireCurrent(context)
            mutable.value = KidsLiveBrowseState(channels.channels, loading = false)
        } catch (error: CancellationException) { throw error
        } catch (_: Exception) {
            mutable.value = KidsLiveBrowseState(loading = false,
                error = "Child Live TV is unavailable. A parent can check the selected guide and age limit in the server's Kids Settings.")
        } finally { busy = false }
    }
}

@Composable
fun rememberKidsLiveBrowser(): KidsLiveBrowser {
    val context by ProfileDataContext.changes.collectAsState()
    val wakes by AppSession.wakes.collectAsState()
    val browser = remember(context) { KidsLiveBrowser(context) }
    LaunchedEffect(browser, wakes) { browser.load() }
    return browser
}
