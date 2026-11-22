package com.strmr.mobile.rooms

import com.strmr.mobile.ui.shell.Route
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.player.PlaybackRequest
import com.strmr.tv.rooms.WatchRoomTarget
import java.io.File
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

/** No Android runtime claim: route data and actual platform caller wiring are checked here. */
class WatchRoomEntryTest {
    private fun source(path: String): String {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        return File(root, path).readText()
    }
    @Test fun detailsAndMoreUseSharedModel() {
        val request = PlaybackRequest(DetailsTarget("tmdb:42", "Synthetic show", false), PlaybackRequest.EpisodeInfo(2, 4))
        val route = Route.WatchRooms(request, 9)
        val body = WatchRoomTarget.create(requireNotNull(route.create), emptyList(), "client-one")
        assertEquals(9L, route.expected)
        assertEquals("4", body.getValue("params").jsonObject.getValue("episodeNumber").jsonPrimitive.content)
        assertTrue(source("mobile/src/main/java/com/strmr/mobile/ui/details/DetailsActions.kt").contains("model.request(state.activeEpisode, null), model.dataContext"))
        assertTrue(source("mobile/src/main/java/com/strmr/mobile/ui/shell/MoreScreen.kt").contains("navigator.push(Route.WatchRooms())"))
    }
    @Test fun piPAndMediaSessionUseOriginalCommands() {
        val pip = source("mobile/src/main/java/com/strmr/mobile/player/PictureInPicture.kt")
        assertTrue(pip.contains("CONTROL_PLAY -> controller.userPlay(true)"))
        assertTrue(pip.contains("CONTROL_PAUSE -> controller.userPlay(false)"))
        val notification = source("mobile/src/main/java/com/strmr/mobile/player/PlayerMediaSession.kt")
        assertTrue(notification.contains("override fun play() = controller.userPlay(true)"))
        assertTrue(notification.contains("override fun pause() = controller.userPlay(false)"))
        assertTrue(notification.contains("override fun seekTo(positionMs: Long) = controller.seekTo(positionMs)"))
    }
    @Test fun castDeniedBeforeAndAfterResolve() {
        val coordinator = source("core/src/main/java/com/strmr/tv/player/PlaybackCoordinator.kt")
        assertTrue(coordinator.contains("val claimed = if (room != null) false"))
        val cast = source("mobile/src/main/java/com/strmr/mobile/cast/CastHandoff.kt")
        assertTrue(cast.contains("fun PlayerSession.castable(): Boolean = room == null"))
        val controls = source("mobile/src/main/java/com/strmr/mobile/player/ui/PlayerControls.kt")
        assertTrue(controls.contains("controller.session.castable()"))
    }
}
