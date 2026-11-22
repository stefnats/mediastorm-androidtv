package com.strmr.tv.api

import org.junit.Assert.*
import org.junit.Test

class LibraryCombinedPlaybackWireTest {
    @Test fun legacySingleEpisodeWireKeepsOrdinaryPlayback() {
        val info = ApiJson.decodeFromString<LibraryPlaybackInfo>("""{"itemId":"single","streamPath":"localmedia:single","durationSeconds":1800}""")
        assertEquals("single", info.itemId)
        assertEquals(1800.0, info.durationSeconds!!, 0.0)
        assertFalse(info.requiresFilePlayback)
        assertTrue(info.combinedEpisodes.isEmpty())
        assertNull(info.playbackNotice)
    }

    @Test fun backendCombinedFileWireRetainsExplicitEpisodeMembershipAndNotice() {
        val info = ApiJson.decodeFromString<LibraryPlaybackInfo>("""{
            "itemId":"combined","streamPath":"localmedia:combined","durationSeconds":3600,
            "combinedEpisodes":[{"seasonNumber":1,"episodeNumber":1},{"seasonNumber":1,"episodeNumber":2}],
            "progressScope":"file",
            "playbackNotice":"Combined episode file; playback starts from the beginning. Progress applies to this file, not to individual episodes.",
            "directStream":true,"sourceType":"local"
        }""")
        assertTrue(info.requiresFilePlayback)
        assertEquals(listOf(LibraryFileEpisode(1,1), LibraryFileEpisode(1,2)), info.combinedEpisodes)
        assertEquals("file", info.progressScope)
        assertTrue(info.playbackNotice!!.contains("not to individual episodes"))
    }

    @Test fun partialFileMetadataCannotFallBackToEpisodeProgress() {
        val explicit = ApiJson.decodeFromString<LibraryPlaybackInfo>("""{"itemId":"a","streamPath":"localmedia:a","progressScope":"file"}""")
        val membership = ApiJson.decodeFromString<LibraryPlaybackInfo>("""{"itemId":"b","streamPath":"localmedia:b","combinedEpisodes":[{"seasonNumber":0,"episodeNumber":1},{"seasonNumber":0,"episodeNumber":2}]}""")
        assertTrue(explicit.requiresFilePlayback)
        assertTrue(membership.requiresFilePlayback)
    }
}
