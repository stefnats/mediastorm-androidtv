package com.strmr.tv.player

import com.strmr.tv.api.ApiError
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.LibraryFileEpisode
import com.strmr.tv.api.LibraryItem
import com.strmr.tv.api.LibraryPlaybackInfo
import com.strmr.tv.api.StartPoint
import com.strmr.tv.library.LibraryVersion
import com.strmr.tv.library.LibraryVersions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CombinedLibraryPlaybackTest {
    private val version = LibraryVersion(LibraryItem("combined",fileName="Synthetic.S01E01-E02.mkv"),"Synthetic TV")
    private val target = DetailsTarget("tmdb:tv:7","Synthetic Show",false,tmdbId="7",imdbId="tt0000007",tvdbId="9")
    private val first = PlaybackRequest.EpisodeInfo(1,1,itemId="episode-1")
    private val second = PlaybackRequest.EpisodeInfo(1,2,itemId="episode-2")
    private val request = PlaybackRequest(target,second,StartPoint.Seconds(450.0),following=listOf(PlaybackRequest.EpisodeInfo(1,3)),
        library=LibraryVersions(emptyList(),mapOf(LibraryVersions.Key(1,2) to listOf(version))),episodeOrderKnown=true)
    private val playback = LibraryPlaybackInfo("combined","localmedia:combined",3600.0,
        listOf(LibraryFileEpisode(1,1),LibraryFileEpisode(1,2)),"file")
    private fun stream(info: LibraryPlaybackInfo = playback) = ResolvedStream(info.streamPath,null,libraryVersion=version,libraryPlayback=info)

    @Test fun secondEpisodeCannotPublishBeforeExplicitCombinedFileChoice() = runTest {
        val consent = CombinedLibraryFileConsent(MutableStateFlow(1L))
        val waiting = async { resolvedLibraryRequest(request,stream(),consent,1) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        val prompt = consent.prompt.value!!
        assertEquals(version.releaseName,prompt.title)
        assertTrue(prompt.message.contains("from the beginning"))
        assertTrue(prompt.message.contains("not to individual episodes"))
        consent.answer(prompt,true)
        val selected = waiting.await()
        assertNull(consent.prompt.value)
        assertNull(selected.episode)
        assertNull(selected.start)
        assertTrue(selected.following.isEmpty())
        assertNull(selected.nextEpisode)
        assertFalse(selected.episodeOrderKnown)
        assertFalse(selected.title.hasCatalogIds)
        assertTrue(selected.isUnmatchedLibraryFile)
        assertTrue(selected.fileOnly)
        assertEquals(playback.streamPath,selected.title.titleId)
        assertEquals(version,selected.libraryVersion)
        val versions = selected.library!!
        assertEquals(listOf(version),versions.movie)
        assertTrue(versions.episodes.isEmpty())
        val progress = selected.progressReport(120.0,3600.0,false,true,playback.streamPath)
        assertEquals("movie",progress["mediaType"]!!.jsonPrimitive.content)
        assertEquals(playback.streamPath,progress["itemId"]!!.jsonPrimitive.content)
        assertEquals(version.releaseName,progress["movieName"]!!.jsonPrimitive.content)
        for (key in listOf("externalIds","seriesId","seasonNumber","episodeNumber","episodeName")) assertNull(key,progress[key])
        val metadata = selected.streamMetadata(playback.streamPath)
        assertEquals(playback.streamPath,metadata["itemId"])
        for (key in listOf("titleId","seriesId","tmdb","imdb","tvdb","seasonNumber","episodeNumber")) assertNull(key,metadata[key])
        assertEquals(second,request.episode)
        assertEquals(450.0,request.startPosition!!,0.0)
    }

    @Test fun combinedFileCannotBecomeACatalogueSourceAfterResumeOrLibraryPointerRemoval() {
        val file = libraryPlaybackRequest(request, version, playback)
        val resumed = SourceSwitchRules.resumeAt(file, 900_000)
        for (candidate in listOf(file, resumed, resumed.copy(libraryVersion = null), resumed.copy(library = null, libraryVersion = null))) {
            assertTrue(candidate.fileOnly)
            assertNull(catalogueSourceRequest(candidate))
            assertNull(candidate.episode)
            assertNull(candidate.nextEpisode)
            assertEquals(playback.streamPath, candidate.progressItemId)
        }
        // Existing single-episode library handoff still preserves its episode and resume identity.
        val ordinary = request.copy(libraryVersion = version)
        val handoff = catalogueSourceRequest(ordinary)!!
        assertNull(handoff.libraryVersion)
        assertFalse(handoff.fileOnly)
        assertEquals(second, handoff.episode)
        assertEquals("episode-2", handoff.progressItemId)
        assertEquals(450.0, handoff.startPosition!!, 0.0)
    }

    @Test fun decliningTheFileNeverReturnsAnEpisodeOrAPlayableRequest() = runTest {
        val consent = CombinedLibraryFileConsent(MutableStateFlow(1L))
        val waiting = async { runCatching { resolvedLibraryRequest(request,stream(),consent,1) } }
        runCurrent()
        consent.answer(consent.prompt.value!!,false)
        assertTrue(waiting.await().exceptionOrNull() is CancellationException)
        assertNull(consent.prompt.value)
    }

    @Test fun profileChangeCancelsTheOriginalFileChoice() = runTest {
        val context = MutableStateFlow(1L)
        val consent = CombinedLibraryFileConsent(context)
        val waiting = async { runCatching { resolvedLibraryRequest(request,stream(),consent,1) } }
        runCurrent()
        context.value=2
        runCurrent()
        assertTrue(waiting.await().exceptionOrNull() is CancellationException)
        assertNull(consent.prompt.value)
    }

    @Test fun staleDialogCannotApproveAnotherFileChoice() = runTest {
        val consent = CombinedLibraryFileConsent(MutableStateFlow(1L))
        val first = async { runCatching { resolvedLibraryRequest(request,stream(),consent,1) } }
        runCurrent()
        val old = consent.prompt.value!!
        consent.answer(old,false)
        first.await()
        val second = async { resolvedLibraryRequest(request,stream(),consent,1) }
        runCurrent()
        consent.answer(old,true)
        runCurrent()
        assertFalse(second.isCompleted)
        consent.answer(consent.prompt.value!!,true)
        assertNull(second.await().episode)
    }

    @Test fun ordinarySingleEpisodeKeepsResumeIdentityAndFollowingWithoutPrompt() = runTest {
        val consent = CombinedLibraryFileConsent(MutableStateFlow(1L))
        val single = playback.copy(combinedEpisodes=emptyList(),progressScope=null)
        val selected = resolvedLibraryRequest(request,stream(single),consent,1)
        assertEquals(request.copy(libraryVersion=version),selected)
        assertEquals(second,selected.episode)
        assertEquals(450.0,selected.startPosition!!,0.0)
        assertEquals(3,selected.nextEpisode!!.episode!!.episode)
        assertNull(consent.prompt.value)
        assertEquals("episode-2",selected.progressReport(5.0,1800.0,false,false,single.streamPath)["itemId"]!!.jsonPrimitive.content)
    }

    @Test fun firstEpisodeOfCombinedFileAlsoUsesOnlyFileProgress() {
        val selected = libraryPlaybackRequest(request.copy(episode=first),version,playback)
        assertNull(selected.episode)
        assertNull(selected.nextEpisode)
        assertEquals(playback.streamPath,selected.progressItemId)
    }

    @Test fun nonLibraryAndLegacySingleFileResolvesRemainUnchanged() = runTest {
        val consent = CombinedLibraryFileConsent(MutableStateFlow(1L))
        assertSame(request,resolvedLibraryRequest(request,ResolvedStream("synthetic",null),consent,1))
        val legacy = ResolvedStream("localmedia:combined",null,libraryVersion=version)
        assertEquals(request.copy(libraryVersion=version),resolvedLibraryRequest(request,legacy,consent,1))
        assertNull(consent.prompt.value)
    }

    @Test fun mismatchedOrEmptyFilePreflightCannotSelectProgressOrPrompt() = runTest {
        val consent = CombinedLibraryFileConsent(MutableStateFlow(1L))
        for (bad in listOf(stream(playback.copy(itemId="another")),stream(playback.copy(streamPath="")),stream().copy(path="localmedia:other"))) {
            val result=runCatching { resolvedLibraryRequest(request,bad,consent,1) }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertNull(consent.prompt.value)
        }
    }

    @Test fun cancellationClosesTheActualPendingFileChoice() = runTest {
        val consent = CombinedLibraryFileConsent(MutableStateFlow(1L))
        val waiting = async { resolvedLibraryRequest(request,stream(),consent,1) }
        runCurrent()
        assertNotNull(consent.prompt.value)
        waiting.cancel()
        runCurrent()
        assertNull(consent.prompt.value)
    }

    @Test fun timeoutNeverApprovesOrStartsACombinedFile() = runTest {
        val consent = CombinedLibraryFileConsent(MutableStateFlow(1L))
        val waiting = async { runCatching { resolvedLibraryRequest(request,stream(),consent,1) } }
        runCurrent()
        advanceTimeBy(60_001)
        runCurrent()
        assertTrue(waiting.await().exceptionOrNull() is ApiError.NotReady)
        assertNull(consent.prompt.value)
    }
}
