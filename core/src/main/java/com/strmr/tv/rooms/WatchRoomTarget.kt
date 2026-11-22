package com.strmr.tv.rooms

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.player.PlaybackRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

/** Shared native details-route fields; never a host's chosen stream or viewer credentials. */
object WatchRoomTarget {
    private val keys = setOf("titleId", "titleName", "mediaType", "imdbId", "tmdbId", "tvdbId",
        "year", "seasonNumber", "episodeNumber", "episodeName")
    fun eligible(request: PlaybackRequest): Boolean = runCatching { params(request); true }.getOrDefault(false)
    private fun params(request: PlaybackRequest): Map<String, String> {
        require(!request.fileOnly && !request.title.fromLibrary && request.libraryVersion == null) {
            "Watch Together needs a catalogue movie or an exact episode."
        }
        require(Regex("(tmdb|tvdb|imdb):[A-Za-z0-9:_-]{1,256}").matches(request.title.titleId))
        val ep = request.episode
        require(request.title.isMovie == (ep == null)) { "Select an exact episode first." }
        if (ep != null) {
            require(ep.season in 0..99999 && ep.episode in 1..99999)
            require(ep.numbering?.ordering?.lowercase().orEmpty() in setOf("", "official", "aired", "default")) {
                "This episode order is not supported in rooms. Choose the standard order first."
            }
        }
        return buildMap {
            put("titleId", request.title.titleId); put("titleName", request.title.name)
            put("mediaType", if (ep == null) "movie" else "series")
            request.title.imdbId?.let { put("imdbId", it) }
            request.title.tmdbId?.let { put("tmdbId", it) }
            request.title.tvdbId?.let { put("tvdbId", it) }
            request.title.year?.let { put("year", it.toString()) }
            ep?.let {
                put("seasonNumber", it.season.toString()); put("episodeNumber", it.episode.toString())
                it.name?.let { name -> put("episodeName", name) }
            }
        }
    }
    private fun itemId(p: Map<String, String>): String = p.getValue("titleId") +
        if (p["mediaType"] == "movie") "" else ":S" + p.getValue("seasonNumber").padStart(2, '0') +
            "E" + p.getValue("episodeNumber").padStart(2, '0')

    fun create(request: PlaybackRequest, invitees: List<String>, clientId: String): JsonObject {
        val p = params(request)
        require(invitees.size <= 64 && invitees.distinct().size == invitees.size && clientId.isNotBlank())
        return buildJsonObject {
            put("title", request.title.name); put("mediaType", if (request.episode == null) "movie" else "episode")
            put("itemId", itemId(p))
            request.title.posterUrl?.let { put("posterUrl", it) }
            request.title.backdropUrl?.let { put("backdropUrl", it) }
            putJsonObject("params") { p.forEach { (k, v) -> put(k, v) } }
            putJsonArray("inviteeProfileIds") { invitees.forEach { add(it) } }
            put("clientId", clientId); put("capabilities", capabilities())
        }
    }

    fun request(room: WatchRoom): PlaybackRequest {
        room.checked()
        val p = room.params
        require(p.keys.all { it in keys } && room.itemId == itemId(p))
        val movie = p["mediaType"] == "movie"
        require(if (movie) room.mediaType == "movie" else p["mediaType"] == "series" && room.mediaType in setOf("episode", "series"))
        val ep = if (movie) null else {
            fun number(key: String): Int {
                val s = p.getValue(key)
                require(Regex("0|[1-9][0-9]{0,4}").matches(s))
                return s.toInt()
            }
            PlaybackRequest.EpisodeInfo(number("seasonNumber"), number("episodeNumber"), name = p["episodeName"])
        }
        val target = DetailsTarget(p.getValue("titleId"), room.title, movie,
            year = p["year"]?.toIntOrNull(), imdbId = p["imdbId"], tmdbId = p["tmdbId"], tvdbId = p["tvdbId"],
            posterUrl = room.posterUrl, backdropUrl = room.backdropUrl)
        return PlaybackRequest(target, ep).also { params(it) }
    }

    fun capabilities() = buildJsonObject {
        put("nativePlayback", true); put("stateSync", true); put("protocolVersion", 1)
    }
}
