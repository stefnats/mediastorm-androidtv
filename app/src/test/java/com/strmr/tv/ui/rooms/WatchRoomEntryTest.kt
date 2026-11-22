package com.strmr.tv.ui.rooms

import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.player.PlaybackRequest
import com.strmr.tv.rooms.WatchRoomTarget
import com.strmr.tv.ui.Page
import java.io.File
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** Pure route/target tests plus source-wiring guards. These do not claim a real TV decoder run. */
class WatchRoomEntryTest {
    private fun source(path: String): String {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return File(root, path).readText()
    }
    @Test fun navigationAndSelectedEpisodeUseRealModel() {
        val request = PlaybackRequest(DetailsTarget("tmdb:42", "Synthetic show", false), PlaybackRequest.EpisodeInfo(2, 3))
        val page = Page.WatchRooms(request, 7)
        val body = WatchRoomTarget.create(requireNotNull(page.create), emptyList(), "client-one")
        assertEquals(7L, page.expected)
        assertEquals("3", body.getValue("params").jsonObject.getValue("episodeNumber").jsonPrimitive.content)
        assertTrue(source("app/src/main/java/com/strmr/tv/ui/Navigation.kt").contains("is Page.WatchRooms -> com.strmr.tv.ui.rooms.WatchRoomsScreen"))
        assertTrue(source("app/src/main/java/com/strmr/tv/ui/details/DetailsScreen.kt").contains("model.request(episode, null), model.dataContext"))
    }
    @Test fun remoteButtonsUseExplicitPort() {
        val chrome = source("app/src/main/java/com/strmr/tv/player/PlayerChrome.kt")
        assertTrue(chrome.contains("Key.MediaPlay -> { controller.userPlay(true)"))
        assertTrue(chrome.contains("Key.MediaPause -> { controller.userPlay(false)"))
        assertTrue(chrome.contains("else controller.userPlay(!player.playWhenReady)"))
        val controller = source("core/src/main/java/com/strmr/tv/player/PlayerController.kt")
        assertTrue(controller.contains("if (room != null) room.command(playing)"))
        assertTrue(controller.contains("if (playing) player.play() else player.pause()"))
    }
}
