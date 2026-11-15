package com.strmr.tv.api

import org.junit.Assert.*
import org.junit.Test

class AutomaticLibrarySettingsTest {
    @Test fun `profile effective preference and response-only capability decode independently`() {
        val user = ApiJson.decodeFromString<UserPlayback>("""{"playback":{"preferLocalFiles":false},"automaticLibrarySelectionSupported":true}""")
        val settings = mergePlaybackSettings(user.playback, PlaybackFields(preferLocalFiles = true), user.automaticLibrarySelectionSupported)
        assertEquals(false, settings.preferLocalFiles)
        assertTrue(settings.automaticLibrarySelectionSupported)
    }

    @Test fun `device source preference cannot override profile or fill missing profile evidence`() {
        assertEquals(true, mergePlaybackSettings(PlaybackFields(preferLocalFiles = true), PlaybackFields(preferLocalFiles = false)).preferLocalFiles)
        assertNull(mergePlaybackSettings(null, PlaybackFields(preferLocalFiles = true)).preferLocalFiles)
        assertNull(mergePlaybackSettings(null, null).preferLocalFiles)
    }

    @Test fun `legacy and unavailable profile do not claim the automatic library capability`() {
        val legacy = ApiJson.decodeFromString<UserPlayback>("""{"playback":{"preferLocalFiles":true}}""")
        assertFalse(legacy.automaticLibrarySelectionSupported)
        assertFalse(mergePlaybackSettings(legacy.playback, null).automaticLibrarySelectionSupported)
        assertFalse(mergePlaybackSettings(null, null).automaticLibrarySelectionSupported)
        assertFalse(mergePlaybackSettings(null, null).sourcePolicyAvailable)
        assertTrue(mergePlaybackSettings(legacy.playback, null).sourcePolicyAvailable)
    }

    @Test fun `capability at another nesting or in device payload cannot enable automatic mode`() {
        val user = ApiJson.decodeFromString<UserPlayback>("""{"playback":{"automaticLibrarySelectionSupported":true}}""")
        val device = ApiJson.decodeFromString<PlaybackFields>("""{"automaticLibrarySelectionSupported":true,"preferLocalFiles":true}""")
        val settings = mergePlaybackSettings(user.playback, device, user.automaticLibrarySelectionSupported)
        assertFalse(settings.automaticLibrarySelectionSupported)
        assertNull(settings.preferLocalFiles)
    }

    @Test fun `optional automatic source status and exact target are additive to old responses`() {
        val legacy = ApiJson.decodeFromString<PrequeueStatus>("""{"prequeueId":"old","status":"ready","streamPath":"localmedia:synthetic"}""")
        assertFalse(legacy.automaticLibrarySelection)
        assertNull(legacy.librarySourceType)
        val current = ApiJson.decodeFromString<PrequeueStatus>("""{"prequeueId":"new","status":"ready","streamPath":"plexmedia:synthetic","automaticLibrarySelection":true,"libraryItemId":"synthetic","librarySourceType":"plex","targetEpisode":{"seasonNumber":1,"episodeNumber":2,"episodeId":"tvdb:episode:123","numbering":{"seriesId":"tvdb:series:74852","ordering":"official"}}}""")
        assertTrue(current.automaticLibrarySelection)
        assertEquals("plex", current.librarySourceType)
        assertEquals("tvdb:episode:123", current.targetEpisode!!.episodeId)
        assertEquals("tvdb:series:74852", current.targetEpisode.numbering!!.seriesId)
    }
}
