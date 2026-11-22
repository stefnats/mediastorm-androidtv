package com.strmr.tv.api

import com.strmr.tv.app.Config
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

/**
 * The details-page info line settings in effect (`display.showDetails*`): this device's choice
 * (Settings › Details Page), else the server's ([detailsInfoServer]), else off.
 */
data class DetailsInfoSettings(
    /** The prequeued release's name. */
    val showFilename: Boolean = false,
    /** The audio and subtitle tracks Play uses. */
    val showTracks: Boolean = false,
) {
    val any: Boolean get() = showFilename || showTracks
}

@Serializable
private data class DetailsInfoFields(
    val showDetailsPrequeueFilename: Boolean? = null,
    val showDetailsSelectedTracks: Boolean? = null,
)

@Serializable
private data class UserDetailsInfo(val display: DetailsInfoFields? = null)

/** The server's values (the device's admin setting, else the profile's); null members when neither sets one. */
data class DetailsInfoServer(val showFilename: Boolean? = null, val showTracks: Boolean? = null)

suspend fun ApiClient.detailsInfoServer(expectedContext: Long = ProfileDataContext.current): DetailsInfoServer = coroutineScope {
    val (path, profile) = inContext(expectedContext) { profilePath to profileId }
    val user = async {
        vodOptionalRead { send<UserDetailsInfo>("GET", "$path/settings", promptOnLock = false, timeoutMs = 10_000, expectedContext = expectedContext) }?.display
    }
    val device = async {
        vodOptionalRead {
            send<DetailsInfoFields>("GET", "/api/clients/${segment(Config.clientId)}/settings", mapOf("userId" to profile),
                                    promptOnLock = false, timeoutMs = 10_000, expectedContext = expectedContext)
        }
    }
    val (u, d) = user.await() to device.await()
    ProfileDataContext.requireCurrent(expectedContext)
    DetailsInfoServer(
        showFilename = d?.showDetailsPrequeueFilename ?: u?.showDetailsPrequeueFilename,
        showTracks = d?.showDetailsSelectedTracks ?: u?.showDetailsSelectedTracks,
    )
}
