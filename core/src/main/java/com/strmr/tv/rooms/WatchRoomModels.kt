package com.strmr.tv.rooms

import java.time.Instant
import kotlinx.serialization.Serializable

/** Coordination DATA. Every viewer still resolves and authorizes their own media. */
@Serializable
data class WatchRoom(
    val id: String,
    val creatorProfileId: String,
    val creatorName: String = "",
    val title: String,
    val mediaType: String,
    val itemId: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val params: Map<String, String>,
    val status: String,
    val waitingForReady: Boolean,
    val position: Double,
    val duration: Double,
    val revision: Long,
    val anchorUpdatedAt: String,
    val expiresAt: String,
    val members: List<WatchRoomMember>,
) {
    fun checked(): WatchRoom = apply {
        require(id.isNotBlank() && id.length <= 256 && revision >= 0)
        require(status in setOf("lobby", "playing", "paused", "ended"))
        roomMilliseconds(position); roomMilliseconds(duration)
        require(params.size <= 64 && members.size <= 256)
        require(params.all { it.key.length <= 256 && it.value.length <= 4096 })
        Instant.parse(anchorUpdatedAt); Instant.parse(expiresAt)
    }
    fun alive(now: Instant = Instant.now()) = status != "ended" && now.isBefore(Instant.parse(expiresAt))
    fun member(profile: String, client: String): WatchRoomMember? =
        members.singleOrNull { it.profileId == profile && it.joined && it.clientId == client }
}

@Serializable
data class WatchRoomMember(
    val profileId: String,
    val name: String = "",
    val clientId: String? = null,
    val isCreator: Boolean = false,
    val isGuest: Boolean = false,
    val ready: Boolean = false,
    val buffering: Boolean = false,
    val joined: Boolean = false,
    val present: Boolean = false,
)

@Serializable
data class WatchRoomAccountInvite(
    val id: String,
    val roomId: String,
    val title: String = "",
    val creatorName: String = "",
    val inviteeUsername: String? = null,
    val status: String,
    val expiresAt: String,
)

internal fun roomMilliseconds(seconds: Double): Long {
    require(seconds.isFinite() && seconds >= 0 && seconds < Long.MAX_VALUE.toDouble() / 1000.0) {
        "The room has an unsupported timeline."
    }
    return (seconds * 1000.0).toLong()
}
