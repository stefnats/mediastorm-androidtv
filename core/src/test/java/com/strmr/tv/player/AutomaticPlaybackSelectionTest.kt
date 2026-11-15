package com.strmr.tv.player

import com.strmr.tv.api.*
import com.strmr.tv.library.LibraryVersion
import com.strmr.tv.library.LibraryVersions
import com.strmr.tv.library.ProfileDataContext
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomaticPlaybackSelectionTest {
    private val title = DetailsTarget("tmdb:series:41", "Synthetic series", false, tmdbId = "41")
    private val first = PlaybackRequest.EpisodeInfo(1, 1, itemId = "tmdb:tv:41:s01e01",
        numbering = EpisodeNumbering("tvdb:series:74852", "official"), metadataEpisodeId = "tvdb:episode:123")
    private val second = first.copy(season = 2, episode = 1, itemId = "tmdb:tv:41:s02e01", metadataEpisodeId = "tvdb:episode:456")
    private fun copies(source: String = "plex", count: Int = 1) = LibraryVersions(emptyList(), listOf(first, second).associate { episode ->
        LibraryVersions.Key(episode.season, episode.episode) to (1..count).map { i ->
            LibraryVersion(LibraryItem("$source-${episode.season}-${episode.episode}-$i", sourceType = source,
                seasonNumber = episode.season, episodeNumber = episode.episode), "Synthetic library")
        }
    })
    private fun request(source: String = "plex", count: Int = 1) = PlaybackRequest(title, first, following = listOf(second), library = copies(source, count))

    private inner class Fixture(val test: TestScope) {
        val expected = ProfileDataContext.current
        var playback = PlaybackSettings(automaticLibrarySelectionSupported = true)
        var legacy: Boolean? = null
        var preferenceReads = 0
        val bodies = mutableListOf<JsonObject>()
        val contexts = mutableListOf<Long>()
        val libraryReads = mutableListOf<String>()
        var policyRevision = 0
        private val serverKeys = mutableMapOf<String, String>()
        private val preparedBodies = mutableMapOf<String, JsonObject>()
        var begin: suspend (JsonObject, Long) -> String = { body, context ->
            bodies += body; contexts += context
            serverKeys.getOrPut("$body|$policyRevision") {
                val id = "p-${serverKeys.size + 1}"
                preparedBodies[id] = body
                id
            }
        }
        var reply: suspend (String, Long) -> PrequeueStatus = { id, context -> contexts += context; ready(id) }
        fun ready(id: String): PrequeueStatus {
            val body = preparedBodies.getValue(id)
            val automatic = body["automaticLibrarySelection"]?.jsonPrimitive?.boolean == true
            val target = body["seasonNumber"]?.jsonPrimitive?.int?.let { season ->
                PrequeueStatus.TargetEpisode(season, body["episodeNumber"]!!.jsonPrimitive.int,
                    body["episodeId"]?.jsonPrimitive?.content,
                    body["numbering"]?.let { ApiJson.decodeFromJsonElement<EpisodeNumbering>(it) })
            }
            return PrequeueStatus(id, "ready", targetEpisode = target, streamPath = "plexmedia:synthetic-$id",
                automaticLibrarySelection = automatic, librarySourceType = "plex", libraryItemId = "synthetic-$id")
        }
        val prequeue = PrequeueResolver(test.backgroundScope, { _, _ -> }, { playback },
            { body, context -> begin(body, context) }, { id, context -> reply(id, context) }, { "synthetic-profile" }, { "synthetic-client" },
            sourcePlan = { request, settings, context -> selectPlaybackSource(request, settings, context) { _, _ -> preferenceReads++; legacy } },
            now = { test.testScheduler.currentTime })
        val resolver = CatalogPlaybackResolver(prequeue::resolve,
            library = { version, context -> contexts += context; libraryReads += version.id; ResolvedStream("plexmedia:${version.id}", null, pickTracksOnDevice = true) },
            legacyPreference = { _, _ -> preferenceReads++; legacy })
        suspend fun resolve(request: PlaybackRequest = request(), chooser: (() -> Unit)? = null) =
            resolver.resolve(request, playback, null, expected, chooser)
    }

    @Test fun `supported ordinary Play ignores blind library first and client hierarchy guesses`() = runTest {
        for (source in listOf("local", "plex", "jellyfin")) for (preference in listOf<Boolean?>(true, false, null)) {
            val f = Fixture(this); f.playback = f.playback.copy(preferLocalFiles = preference)
            val selected = f.resolve(request(source))!!
            assertTrue(f.bodies.single()["automaticLibrarySelection"]!!.jsonPrimitive.boolean)
            assertTrue(f.libraryReads.isEmpty()); assertEquals(0, f.preferenceReads)
            assertEquals("p-1", selected.prequeueId); assertFalse(selected.pickTracksOnDevice)
            assertTrue(f.contexts.all { it == f.expected })
        }
    }
    @Test fun `supported normal Play with several library versions never opens the legacy picker`() = runTest {
        val f = Fixture(this); var choices = 0
        assertNotNull(f.resolve(request(count = 2)) { choices++ })
        assertEquals(0, choices); assertEquals(1, f.bodies.size)
    }
    @Test fun `markerless legacy Play retains multi-version picker without starting playback`() = runTest {
        val f = Fixture(this); f.playback = f.playback.copy(automaticLibrarySelectionSupported = false); var choices = 0
        assertNull(f.resolve(request(count = 2)) { choices++ })
        assertEquals(1, choices); assertTrue(f.bodies.isEmpty()); assertTrue(f.libraryReads.isEmpty())
    }
    @Test fun `markerless legacy Play preserves inherited library first without making profile policy claims`() = runTest {
        for (preference in listOf<Boolean?>(true, false, null)) {
            val f = Fixture(this); f.playback = f.playback.copy(automaticLibrarySelectionSupported = false, preferLocalFiles = preference)
            val selected = f.resolve()!!
            assertEquals(listOf("plex-1-1-1"), f.libraryReads); assertTrue(f.bodies.isEmpty())
            assertEquals("plex-1-1-1", selected.libraryVersion!!.id)
        }
    }
    @Test fun `legacy content false is checked centrally for Play and each prefetched next episode`() = runTest {
        val f = Fixture(this); f.playback = f.playback.copy(automaticLibrarySelectionSupported = false); f.legacy = false
        val next = request().nextEpisode!!
        f.prequeue.prefetch(next); runCurrent()
        val result = f.resolve(next)!!
        assertEquals(2, f.bodies.size); assertTrue(f.bodies.all { !it.containsKey("automaticLibrarySelection") })
        assertEquals("p-1", result.prequeueId); assertTrue(f.libraryReads.isEmpty()); assertEquals(2, f.preferenceReads)
    }
    @Test fun `deliberate Versions bypasses automatic policy and keeps library metadata`() = runTest {
        val f = Fixture(this); val request = request(); val picked = request.library!!.best(1,1)!!
        val result = f.resolve(request.copy(libraryVersion = picked))!!
        assertEquals(picked, result.libraryVersion); assertTrue(result.pickTracksOnDevice)
        assertTrue(f.bodies.isEmpty()); assertEquals(0, f.preferenceReads)
    }
    @Test fun `library callback retains original context and real API rejects replacement before IO`() = runTest {
        val expected = ProfileDataContext.current
        var libraryCalls = 0
        val resolver = CatalogPlaybackResolver(prequeue = { _, _, _ -> error("Unexpected prequeue") },
            library = { version, captured ->
                libraryCalls++
                assertEquals(expected, captured)
                // This switch is after selection's guard and immediately before the actual API.
                ProfileDataContext.invalidate()
                val info = ApiClient.libraryPlayback(version.id, captured)
                ResolvedStream(info.streamPath, null)
            })
        try { resolver.resolve(request().copy(title = title.copy(fromLibrary = true)), null, null, expected); fail()
        } catch (_: CancellationException) { }
        assertEquals(1, libraryCalls)
    }
    @Test fun `real library API rejects stale caller context even before global profile snapshot`() = runTest {
        val expected = ProfileDataContext.current
        ProfileDataContext.invalidate()
        try { ApiClient.libraryPlayback("synthetic-library-item", expected); fail()
        } catch (_: CancellationException) { }
    }
    @Test fun `late library callback cannot publish under a replacement context`() = runTest {
        val expected = ProfileDataContext.current
        val gate = CompletableDeferred<Unit>()
        val resolver = CatalogPlaybackResolver(prequeue = { _, _, _ -> error("Unexpected prequeue") },
            library = { _, captured ->
                assertEquals(expected, captured)
                withContext(NonCancellable) { gate.await() }
                ResolvedStream("plexmedia:synthetic", null)
            })
        val task = async { resolver.resolve(request().copy(title = title.copy(fromLibrary = true)), null, null, expected) }
        runCurrent(); ProfileDataContext.invalidate(); gate.complete(Unit)
        try { task.await(); fail() } catch (_: CancellationException) { }
    }
    @Test fun `fromLibrary normal Play remains a deliberate library path across Up Next`() = runTest {
        val f = Fixture(this); val fromLibrary = request().copy(title = title.copy(fromLibrary = true))
        f.resolve(fromLibrary); f.resolve(fromLibrary.nextEpisode!!)
        assertEquals(listOf("plex-1-1-1", "plex-2-1-1"), f.libraryReads)
        assertTrue(f.bodies.isEmpty()); assertEquals(0, f.preferenceReads)
        f.prequeue.prewarm(fromLibrary.title); runCurrent(); assertTrue(f.bodies.isEmpty())
    }
    @Test fun `Stream Instead preserves search only intent and available versions into following episodes`() = runTest {
        val f = Fixture(this); val search = request().copy(sourceSelection = PlaybackRequest.SourceSelection.SEARCH_ONLY)
        val next = search.nextEpisode!!
        assertSame(search.library, next.library)
        assertEquals(PlaybackRequest.SourceSelection.SEARCH_ONLY, next.sourceSelection)
        f.prequeue.prefetch(next); runCurrent(); val selected = f.resolve(next)!!
        assertTrue(f.bodies.all { !it.containsKey("automaticLibrarySelection") })
        assertEquals(2, f.bodies.size)
        assertEquals("p-1", selected.prequeueId); assertEquals(0, f.preferenceReads); assertTrue(f.libraryReads.isEmpty())
    }
    @Test fun `supported next episode prefetch adopts same opt in preparation with nonempty library`() = runTest {
        val f = Fixture(this); val next = request().nextEpisode!!
        f.prequeue.prefetch(next); runCurrent(); val result = f.resolve(next)!!
        assertEquals(2, f.bodies.size); assertEquals("p-1", result.prequeueId)
        assertEquals(2, f.bodies.last()["seasonNumber"]!!.jsonPrimitive.int)
        assertEquals("tvdb:episode:456", f.bodies.last()["episodeId"]!!.jsonPrimitive.content)
        assertEquals("tmdb:tv:41:s02e01", next.progressItemId)
    }
    @Test fun `details warm and Play carry same automatic mode even without client library winner IDs`() = runTest {
        val f = Fixture(this); val movie = PlaybackRequest(DetailsTarget("tmdb:movie:41", "Synthetic", true), library = LibraryVersions(listOf(LibraryVersion(LibraryItem("manual"),"Library")), emptyMap()))
        f.prequeue.prewarm(movie.title); runCurrent()
        assertEquals(WarmPrequeue.Phase.READY, f.prequeue.warm.value!!.phase)
        val stream = f.resolve(movie)!!
        assertEquals(2, f.bodies.size); assertEquals("p-1", stream.prequeueId)
        assertFalse(f.bodies.last().containsKey("libraryItemId"))
    }
    @Test fun `prequeue on open false suppresses both supported details warm and next prefetch`() = runTest {
        val f = Fixture(this); f.playback = f.playback.copy(prequeueOnOpen = false)
        f.prequeue.prewarm(title); runCurrent(); assertTrue(f.bodies.isEmpty())
        f.prequeue.prefetch(request().nextEpisode!!); runCurrent(); assertTrue(f.bodies.isEmpty())
        assertNotNull(f.resolve()); assertEquals(1, f.bodies.size)
    }
    @Test fun `legacy prefetch does not prepare a library copy but normal Play remains deliberate`() = runTest {
        val f = Fixture(this); f.playback = f.playback.copy(automaticLibrarySelectionSupported = false)
        f.prequeue.prefetch(request().nextEpisode!!); runCurrent(); assertTrue(f.bodies.isEmpty())
        f.resolve(request().nextEpisode!!); assertEquals(listOf("plex-2-1-1"), f.libraryReads)
    }
    @Test fun `capability transition cannot adopt a legacy warm preparation for automatic Play`() = runTest {
        val f = Fixture(this); f.playback = f.playback.copy(automaticLibrarySelectionSupported = false); f.legacy = false
        f.prequeue.prefetch(request()); runCurrent(); f.playback = f.playback.copy(automaticLibrarySelectionSupported = true)
        val selected = f.resolve()!!
        assertEquals(2, f.bodies.size); assertEquals("p-2", selected.prequeueId)
        assertFalse(f.bodies[0].containsKey("automaticLibrarySelection")); assertTrue(f.bodies[1]["automaticLibrarySelection"]!!.jsonPrimitive.boolean)
    }
    @Test fun `warm automatic mode is not adopted after an intentional Stream Instead`() = runTest {
        val f = Fixture(this); f.prequeue.prefetch(request()); runCurrent()
        val selected = f.resolve(request().copy(sourceSelection = PlaybackRequest.SourceSelection.SEARCH_ONLY))!!
        assertEquals("p-2", selected.prequeueId); assertEquals(2, f.bodies.size)
        assertFalse(f.bodies.last().containsKey("automaticLibrarySelection"))
    }
    @Test fun `a server ignoring opt in cannot silently play an unsafe legacy preparation`() = runTest {
        val f = Fixture(this); f.reply = { id, _ -> f.ready(id).copy(automaticLibrarySelection = false) }
        try { f.resolve(); fail() } catch (_: ApiError.NotReady) { }
        assertEquals(1, f.bodies.size); assertTrue(f.libraryReads.isEmpty())
    }
    @Test fun `equal S E but different leaf or numbering cannot reuse a warm preparation`() = runTest {
        for (wrong in listOf(first.copy(metadataEpisodeId = "tvdb:episode:999"), first.copy(numbering = EpisodeNumbering("tvdb:series:999", "official")), first.copy(numbering = EpisodeNumbering("tvdb:series:74852", "dvd")))) {
            val f = Fixture(this); f.prequeue.prefetch(request().copy(episode = wrong)); runCurrent()
            val selected = f.resolve()!!
            assertEquals(2, f.bodies.size); assertEquals("p-2", selected.prequeueId)
        }
    }
    @Test fun `title warm target with wrong leaf is discarded before Play`() = runTest {
        val f = Fixture(this)
        f.reply = { id, _ -> f.ready(id).let { if (id == "p-1") it.copy(targetEpisode = PrequeueStatus.TargetEpisode(1,1,"tvdb:episode:999",first.numbering)) else it } }
        f.prequeue.prewarm(title); runCurrent()
        assertEquals("p-2", f.resolve()!!.prequeueId)
        assertEquals(2, f.bodies.size)
    }
    @Test fun `legacy warm restart GET404 then POST failure settles unavailable without uncaught exception`() = runTest {
        val f = Fixture(this); var starts = 0
        f.begin = { body, context -> if (++starts > 1) throw IOException("Synthetic timeout") else {f.bodies += body; f.contexts += context; "p-1"} }
        f.reply = { _, _ -> throw ApiError.Http(HttpFailure(404, "Synthetic gone")) }
        f.prequeue.prewarm(title); runCurrent()
        assertEquals(2, starts); assertEquals(WarmPrequeue.Phase.UNAVAILABLE, f.prequeue.warm.value!!.phase)
    }
    @Test fun `warm invalidation preserves an adopted active stream and original report context`() = runTest {
        val f = Fixture(this); f.prequeue.prefetch(request()); runCurrent()
        val selected = f.resolve()!!; f.prequeue.invalidateWarm()
        assertNull(f.prequeue.warm.value)
        assertEquals("p-1", selected.prequeueId); assertEquals("plexmedia:synthetic-p-1", selected.path)
        assertEquals("p-1", f.ready(selected.prequeueId!!).prequeueId)
    }
    @Test fun `confirmed audio preference save retires warm without losing the active report envelope`() = runTest {
        val f = Fixture(this); val selected = f.resolve()!!
        f.prequeue.prewarm(title); runCurrent()
        var saved: Long? = null
        savePlaybackAudioLanguage(title.titleId, "series", "fra", f.expected,
            save = { _, _, _, context -> saved = context }, invalidate = f.prequeue::invalidateWarm)
        assertEquals(f.expected, saved); assertNull(f.prequeue.warm.value)
        assertEquals("p-1", selected.prequeueId); assertEquals("ready", f.ready("p-1").status)
    }
    @Test fun `failed or stale audio save cannot retire a replacement profile warm`() = runTest {
        val expected = ProfileDataContext.current; var invalidations = 0
        try { savePlaybackAudioLanguage(title.titleId,"series","fra",expected,
            save = { _, _, _, _ -> throw IOException("Synthetic failed save") }, invalidate = { invalidations++ }); fail()
        } catch (_: IOException) { }
        assertEquals(0, invalidations)
        try { savePlaybackAudioLanguage(title.titleId,"series","fra",expected,
            save = { _, _, _, _ -> ProfileDataContext.invalidate() }, invalidate = { invalidations++ }); fail()
        } catch (_: CancellationException) { }
        assertEquals(0, invalidations)
    }
    @Test fun `retired ready ID stays reportable but Play asks the server for current policy before adoption`() = runTest {
        val f = Fixture(this); f.prequeue.prefetch(request()); runCurrent()
        assertEquals("ready", f.ready("p-1").status)
        f.policyRevision++ // Audio/profile/device policy changed; by-ID report context remains.
        val selected = f.resolve()!!
        assertEquals("p-2", selected.prequeueId)
        assertEquals("ready", f.ready("p-1").status)
        assertEquals(2, f.bodies.size)
    }
    @Test fun `late automatic status after profile or server replacement cannot publish a stream`() = runTest {
        val f = Fixture(this); val gate = CompletableDeferred<PrequeueStatus>()
        f.reply = { _, _ -> withContext(NonCancellable) { gate.await() } }
        val task = async { f.resolve() }; runCurrent(); ProfileDataContext.invalidate(); gate.complete(f.ready("p-1"))
        try { task.await(); fail() } catch (_: CancellationException) { }
        assertTrue(f.libraryReads.isEmpty())
    }
    @Test fun `cancelled playback cannot publish after a cancellation masked status response`() = runTest {
        val f = Fixture(this); val gate = CompletableDeferred<PrequeueStatus>(); var published = false
        f.reply = { _, _ -> withContext(NonCancellable) { gate.await() } }
        val task = backgroundScope.launch { f.resolve(); published = true }
        runCurrent(); task.cancel(); gate.complete(f.ready("p-1")); runCurrent()
        assertFalse(published); assertTrue(task.isCancelled)
    }
    @Test fun `profile changed before request prevents both policy reads and prequeue mutations`() = runTest {
        val f = Fixture(this); ProfileDataContext.invalidate()
        try { f.resolve(); fail() } catch (_: CancellationException) { }
        assertTrue(f.bodies.isEmpty()); assertEquals(0, f.preferenceReads)
    }
    @Test fun `real Up Next prefetch and advance preserve actual next leaf and fresh source selection`() = runTest {
        val f = Fixture(this); val advanced = mutableListOf<PlaybackRequest>()
        val original = request().copy(libraryVersion = request().library!!.best(1,1))
        val next = UpNext(original, null, backgroundScope, { null }, readSettings = { f.playback },
            creditsEnabled = { false }, prepareNext = f.prequeue::prefetch, log = {}, onAdvance = { advanced += it })
        next.prefetchIfNear(90_000, 100_000); runCurrent()
        assertEquals(1, f.bodies.size); assertEquals("tvdb:episode:456", f.bodies.single()["episodeId"]!!.jsonPrimitive.content)
        next.advance(next.nextEpisode!!)
        assertEquals(1, advanced.size); assertNull(advanced.single().libraryVersion)
        assertEquals("tmdb:tv:41:s02e01", advanced.single().progressItemId)
        assertEquals("tvdb:episode:456", advanced.single().episode!!.metadataEpisodeId)
        assertEquals("p-1", f.resolve(advanced.single())!!.prequeueId)
        assertEquals(2, f.bodies.size)
        next.cancel()
    }
    @Test fun `Up Next does not prefetch or advance after profile replacement`() = runTest {
        val f = Fixture(this); var advanced = false
        val next = UpNext(request(), null, backgroundScope, { null }, readSettings = { f.playback },
            creditsEnabled = { false }, prepareNext = f.prequeue::prefetch, log = {}, onAdvance = { advanced = true })
        ProfileDataContext.invalidate(); next.prefetchIfNear(99_000,100_000); next.advance(next.nextEpisode!!); runCurrent()
        assertFalse(advanced); assertTrue(f.bodies.isEmpty()); next.cancel()
    }
    @Test fun `real details conversion keeps metadata leaf separate from progress for both current and next episode`() = runTest {
        val actualFirst = Episode("tvdb:episode:123", seasonNumber = 1, episodeNumber = 1,
            itemId = "tmdb:tv:41:s01e01", numbering = first.numbering, airedDate = "2020-01-01")
        val actualSecond = Episode("tvdb:episode:456", seasonNumber = 2, episodeNumber = 1,
            itemId = "tmdb:tv:41:s02e01", numbering = second.numbering, airedDate = "2020-01-02")
        val model = com.strmr.tv.ui.details.DetailsModel(title, backgroundScope)
        model.reloadForReminder { DetailsBundle(seriesDetails = SeriesDetails(Title(title.titleId, title.name, mediaType = "series"),
            seasons = listOf(Season("s1", number = 1, episodes = listOf(actualFirst)), Season("s2", number = 2, episodes = listOf(actualSecond))))) }
        val current = model.request(actualFirst, null)
        for ((request, leaf, progress) in listOf(Triple(current,"tvdb:episode:123","tmdb:tv:41:s01e01"),
            Triple(current.nextEpisode!!,"tvdb:episode:456","tmdb:tv:41:s02e01"))) {
            assertEquals(leaf, request.episode!!.metadataEpisodeId)
            assertEquals(leaf, prequeueRequestBody(request, true, "synthetic-profile", "synthetic-client")["episodeId"]!!.jsonPrimitive.content)
            assertEquals(progress, request.progressItemId)
            assertEquals(progress, request.progressReport(2.0, 100.0, false, false)["itemId"]!!.jsonPrimitive.content)
        }
    }
    @Test fun `a failed automatic selection does not silently play an unchecked library copy`() = runTest {
        val f = Fixture(this); f.begin = { _, _ -> throw IOException("Synthetic unavailable preparation") }
        try { f.resolve(); fail() } catch (_: IOException) { }
        assertTrue(f.libraryReads.isEmpty())
    }
    @Test fun `unavailable source policy never masquerades as a confirmed markerless server`() = runTest {
        val f = Fixture(this); f.playback = f.playback.copy(automaticLibrarySelectionSupported = false, sourcePolicyAvailable = false)
        try { f.resolve(); fail() } catch (_: ApiError.NotReady) { }
        assertTrue(f.libraryReads.isEmpty()); assertTrue(f.bodies.isEmpty()); assertEquals(0, f.preferenceReads)
        assertNotNull(f.resolve(request().copy(sourceSelection = PlaybackRequest.SourceSelection.SEARCH_ONLY)))
        assertFalse(f.bodies.single().containsKey("automaticLibrarySelection"))
        assertNotNull(f.resolve(request().copy(title = title.copy(fromLibrary = true))))
        assertEquals(1, f.libraryReads.size)
    }
    @Test fun `mode true library status carries original server-selected tracks and envelope`() = runTest {
        val f = Fixture(this)
        val tracks = TrackSelection(audio = SelectedTrack(streamIndex = 4, typeIndex = 1, language = "fra"))
        f.reply = { id, _ -> f.ready(id).copy(trackSelection = tracks, serviceType = "jellyfin") }
        val selected = f.resolve()!!
        assertSame(tracks, selected.tracks)
        assertEquals("p-1", selected.prequeueId)
        assertEquals("jellyfin", selected.source!!.serviceType)
        assertFalse(selected.pickTracksOnDevice)
    }
    @Test fun `preparation key separates modes intents exact owner and leaf`() {
        val request = request()
        assertNotEquals(request.preparationKey(true), request.preparationKey(false))
        assertNotEquals(request.prequeueKey, request.copy(sourceSelection = PlaybackRequest.SourceSelection.SEARCH_ONLY).prequeueKey)
        assertNotEquals(request.prequeueKey, request.copy(episode = first.copy(metadataEpisodeId = "other-leaf")).prequeueKey)
        assertNotEquals(request.prequeueKey, request.copy(episode = first.copy(numbering = EpisodeNumbering("tvdb:series:42","official"))).prequeueKey)
    }
    @Test fun `native wire fixtures distinguish automatic leaf ID from legacy progress identity`() {
        val output = System.getProperty("automaticLibraryContractsDir")?.let(::File)
        output?.mkdirs()
        for ((name, request, automatic) in listOf(
            Triple("automatic-official-series", request(), true),
            Triple("automatic-next-series", request().nextEpisode!!, true),
            Triple("automatic-tvdb-series", request().copy(title = title.copy(titleId = "tvdb:series:74852", tmdbId = null, tvdbId = "74852")), true),
            Triple("automatic-dvd-series", request().copy(episode = first.copy(numbering = EpisodeNumbering("tvdb:series:74852", "dvd"))), true),
            Triple("automatic-movie", PlaybackRequest(DetailsTarget("tmdb:movie:41","Synthetic",true)), true),
            Triple("legacy-series", request(), false),
            Triple("stream-instead-series", request().copy(sourceSelection = PlaybackRequest.SourceSelection.SEARCH_ONLY), false),
        )) {
            val body = prequeueRequestBody(request, automatic, "synthetic-profile", "synthetic-client")
            if (automatic && request.episode != null) {
                assertEquals(request.episode!!.metadataEpisodeId, body["episodeId"]!!.jsonPrimitive.content)
                assertNotEquals(request.progressItemId, body["episodeId"]!!.jsonPrimitive.content)
            } else assertFalse(body.containsKey("episodeId"))
            assertEquals(automatic, body["automaticLibrarySelection"]?.jsonPrimitive?.boolean ?: false)
            output?.resolve("$name.json")?.writeText(body.toString() + "\n")
        }
    }
}
