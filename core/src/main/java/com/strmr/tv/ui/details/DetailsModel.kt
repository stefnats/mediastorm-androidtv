package com.strmr.tv.ui.details

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiError
import com.strmr.tv.api.VodAccessDenial
import com.strmr.tv.api.VodDenialEvents
import kotlinx.coroutines.CoroutineStart
import com.strmr.tv.api.vodDenial
import com.strmr.tv.api.CastMember
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.DetailsBundle
import com.strmr.tv.api.Episode
import com.strmr.tv.api.EpisodeRef
import com.strmr.tv.api.ResumeState
import com.strmr.tv.api.Season
import com.strmr.tv.api.SeriesOrdering
import com.strmr.tv.api.SourceResult
import com.strmr.tv.api.StartPoint
import com.strmr.tv.api.Title
import com.strmr.tv.api.TrailerList
import com.strmr.tv.api.collectionMovies
import com.strmr.tv.api.detailsBundle
import com.strmr.tv.api.formatRuntime
import com.strmr.tv.api.libraryMatches
import com.strmr.tv.api.setSeriesOrdering
import com.strmr.tv.api.setWatched
import com.strmr.tv.api.WatchedSelection
import com.strmr.tv.api.WatchedScope
import com.strmr.tv.api.WatchedAccess
import com.strmr.tv.api.ServerWatchedAccess
import com.strmr.tv.api.performWatched
import com.strmr.tv.api.selectMovieWatched
import com.strmr.tv.api.selectEpisodesWatched
import com.strmr.tv.library.CustomListOperation
import com.strmr.tv.home.CardBadge
import com.strmr.tv.home.ReleaseBadge
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.strmr.tv.ui.AppToast
import com.strmr.tv.library.LibraryVersion
import com.strmr.tv.library.LibraryVersions
import com.strmr.tv.player.ManualSourceTracks
import com.strmr.tv.player.PlaybackCoordinator
import com.strmr.tv.player.PlaybackSessionContext
import com.strmr.tv.player.PlaybackStartupIntent
import com.strmr.tv.player.PlaybackStartupTiming
import com.strmr.tv.player.StartupEntry
import com.strmr.tv.player.StartupOutcome
import com.strmr.tv.player.startupPreparation
import com.strmr.tv.player.PrequeuePick
import com.strmr.tv.player.PlaybackRequest
import com.strmr.tv.player.playLibrary
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A details page's data and decisions, from one `/details-bundle` call: metadata, seasons
 * with per-episode watched / resume, the up-next episode and the server's resume decision
 * (no client thresholds). Reloaded after playback to refresh progress.
 */
class DetailsModel(val target: DetailsTarget, private val scope: CoroutineScope = MainScope(),
    private val retireWarm:(String,Long)->Unit={id,context->PlaybackCoordinator.invalidateWarmSources(id,context)},
    expectedContext: Long = ProfileDataContext.current,
    private val captureStartup: ((PlaybackSessionContext, DetailsTarget) -> PlaybackStartupIntent)? = null,
    private val startupCurrent: () -> Boolean = { true },
    private val lookupLibrary:suspend (DetailsTarget,Long)->List<com.strmr.tv.api.LibraryMatch> = {target,context->ApiClient.libraryMatches(target,context)},
) {
    val dataContext = expectedContext
    private var playJob: Job? = null
    private var readGeneration=0L
    private var denialFence=VodDenialEvents.fence()
    init {
        scope.launch(start=CoroutineStart.UNDISPATCHED) {
            VodDenialEvents.observed.collect {event->
                if(event.attempt.context==dataContext && event.attempt.titleId==target.titleId && event.attempt.sequence>denialFence)retireAccess(event.reason)
            }
        }
    }
    private var libraryGeneration=0L
    var accessGeneration by mutableStateOf(0L)
        private set
    fun acceptsPlaybackAction(generation:Long)=playable && generation==accessGeneration
    var vodDenial by mutableStateOf<VodAccessDenial?>(null)
        private set
    val playable get()=scope.coroutineContext[Job]?.isActive!=false && vodDenial==null && title!=null && dataContext==ProfileDataContext.current

    private fun retireAccess(denial:VodAccessDenial) {
        if(dataContext!=ProfileDataContext.current)return
        readGeneration++;libraryGeneration++;accessGeneration++;playJob?.cancel();retireWarm(target.titleId,dataContext)
        vodDenial=denial;loadError=denial.message;title=null;seasons=emptyList();trailers=null;similar=emptyList();collectionMovies=emptyList()
        primaryEpisode=null;privacyEpisode=null;selectedSeason=null;upNext=null;resume=null;library=null;playChoice=null
        orderings=emptyList();watchedOverrides.clear();serverInWatchlist=null;movieWatched=null
        libraryLookup.complete(null)
    }

    fun sourcePreferenceChanged() {
        ApiClient.inContext(dataContext) {
            playJob?.cancel()
            PlaybackCoordinator.invalidateWarmSources()
            playChoice = null
            AppToast.show("Source preference saved for this profile.")
        }
    }
    var title by mutableStateOf<Title?>(null)
        private set
    val displayPosterUrl get() = PersonalArtwork.store.poster(target.titleId, title?.textPoster?.url ?: title?.poster?.url ?: target.displayPosterUrl)
    val displayBackdropUrl get() = PersonalArtwork.store.backdrop(target.titleId, title?.backdrop?.url ?: target.backdropUrl)
    var seasons by mutableStateOf<List<Season>>(emptyList())
        private set
    var selectedSeason by mutableStateOf<Int?>(null)
    /** The episode the primary button plays: the server's up-next episode, else the first one. */
    var primaryEpisode by mutableStateOf<Episode?>(null)
        private set
    /** The episode spoiler blurring counts from (see [EpisodePrivacy.currentEpisode]). */
    var privacyEpisode by mutableStateOf<Episode?>(null)
        private set
    var loadError by mutableStateOf<String?>(null)
        private set
    var trailers by mutableStateOf<TrailerList?>(null)
        private set
    /** The bundle's `inWatchlist` (null on older servers); WatchlistStore layers toggles on top. */
    var serverInWatchlist by mutableStateOf<Boolean?>(null)
        private set
    /** The bundle's movie `watched` (null on older servers or for series). */
    private var movieWatchedBase by mutableStateOf<Boolean?>(null)
    var movieWatched: Boolean?
        get() = movieOptimism?.takeIf { it.current() }?.selection?.watched ?: movieWatchedBase
        private set(value) { movieWatchedBase = value }
    /** Watched changes made here, shown until the reload after the write lands (by episode ID). */
    val watchedOverrides = mutableStateMapOf<String, Boolean>()
    var watchedError by mutableStateOf<String?>(null)
        private set
    /** More Like This (the bundle's, de-duplicated). */
    var similar by mutableStateOf<List<Title>>(emptyList())
        private set
    /** Movies: the rest of the movie's collection, in release order. */
    var collectionMovies by mutableStateOf<List<Title>>(emptyList())
        private set
    /** Series: the episode orderings on offer (more than one = a choice in More). */
    var orderings by mutableStateOf<List<SeriesOrdering>>(emptyList())
        private set
    /** The ordering the seasons are in, as the server reported it. */
    private var activeOrdering by mutableStateOf<String?>(null)
    /** The ordering picked this visit, asked for explicitly on reload (as the Expo app does). */
    private var pickedOrdering by mutableStateOf<String?>(null)
    private class WatchedOptimism(val selection: WatchedSelection) {
        var active = true
        fun current() = active && runCatching { selection.check(); true }.getOrDefault(false)
    }
    private var movieOptimism by mutableStateOf<WatchedOptimism?>(null)
    private val episodeOptimism = mutableStateMapOf<String, WatchedOptimism>()
    private var watchedRevision = 0L
    private var resumeRevision = 0L
    // State, so the primary button follows a reload after playback (a movie's page has nothing
    // else that changes then).
    private var upNext by mutableStateOf<EpisodeRef?>(null)
    /** Server resume decision for the movie, or for `upNext`. */
    private var resume by mutableStateOf<ResumeState?>(null)
    /** Fixed per page, so the stream-start art order doesn't reshuffle between plays. */
    private val artSeed = Random.nextLong()
    /**
     * The title's copies in the profile's local, Plex and Jellyfin libraries (null: none, or not
     * looked up yet). They win over the prequeue: Play plays a single copy straight away and asks
     * which one when there are several ([playChoice], with Stream Instead), as the Expo app does.
     * Versions lists them all, and Choose Source lists them above the stream results.
     */
    var library by mutableStateOf<LibraryVersions?>(null)
        private set
    /** Completes once the libraries have been asked (with the copies, or null). */
    private var libraryLookup = CompletableDeferred<LibraryVersions?>()

    /** Looks the title up in the libraries, once per page (not on every reload after playback). */
    suspend fun loadLibrary() {
        if (libraryLookup.isCompleted || vodDenial!=null) return
        val pending=libraryLookup;val generation=libraryGeneration
        try {
            ApiClient.inContext(dataContext) {}
            val versions=LibraryVersions.of(lookupLibrary(target,dataContext)).takeUnless {it.isEmpty}
            ApiClient.inContext(dataContext) {if(generation==libraryGeneration && vodDenial==null)library=versions}
            pending.complete(versions)
        } catch(error:CancellationException) {pending.complete(null);throw error
        } catch(error:Exception) {
            if(generation==libraryGeneration)error.vodDenial?.let(::retireAccess)
            pending.complete(null)
        }
    }

    /** Play found several copies: the details page lists them to pick from (null: no question). */
    var playChoice by mutableStateOf<PlayChoice?>(null)

    data class PlayChoice(val episode: Episode?, val start: StartPoint?, val picks: PrequeuePick?)

    /** The copies of a movie (no episode) or of one episode, in the server's order. */
    fun libraryVersions(episode: Episode?): List<LibraryVersion> {
        val all = library ?: return emptyList()
        return if (episode == null) { if (target.isMovie) all.movie else emptyList() } else all.versions(episode.seasonNumber, episode.episodeNumber)
    }

    /** The copies of what the primary button plays. */
    val primaryVersions: List<LibraryVersion> get() = libraryVersions(if (target.isMovie) null else primaryEpisode)

    /** Specials last, so the season chips open on real seasons. */
    val visibleSeasons: List<Season> get() = seasons.filter { it.number > 0 } + seasons.filter { it.number == 0 }
    val currentSeason: Season? get() = visibleSeasons.firstOrNull { it.number == selectedSeason } ?: visibleSeasons.firstOrNull()

    suspend fun load()=load {expected->ApiClient.detailsBundle(target,pickedOrdering,expectedContext=expected)}

    internal suspend fun load(fetch:suspend (Long)->DetailsBundle) {
        try {readAndApply(fetch)} catch(error:CancellationException) {throw error
        } catch(error:Exception) {if(dataContext==ProfileDataContext.current && vodDenial==null && title==null)loadError=error.message}
        if(vodDenial!=null || dataContext!=ProfileDataContext.current)return
        val generation=readGeneration
        title?.collection?.let {collection->
            if(collectionMovies.isEmpty()) {
                try {
                    ApiClient.inContext(dataContext) {}
                    val result=ApiClient.collectionMovies(collection.id)
                    ApiClient.inContext(dataContext) {if(vodDenial==null && generation==readGeneration)collectionMovies=result.distinctBy {it.id}}
                } catch(error:CancellationException) {throw error
                } catch(error:Exception) {if(generation==readGeneration)error.vodDenial?.let(::retireAccess)}
            }
        }
    }

    /** A reminder recovery must prove that fresh page metadata was read successfully. */
    suspend fun reloadForReminder()=reloadForReminder {expected->ApiClient.detailsBundle(target,pickedOrdering,expectedContext=expected)}
    internal suspend fun reloadForReminder(fetch:suspend (Long)->DetailsBundle)=readAndApply(fetch)

    private suspend fun readAndApply(fetch:suspend (Long)->DetailsBundle) {
        val caller=currentCoroutineContext();caller.ensureActive();if(scope.coroutineContext[Job]?.isActive==false)throw CancellationException("Details page closed");ApiClient.inContext(dataContext) {}
        val generation=++readGeneration
        val watchedAtRead=watchedRevision
        val resumeAtRead=resumeRevision
        val authorizationAttempt=VodDenialEvents.begin(target.titleId,dataContext)
        try {
            val bundle=fetch(dataContext)
            ApiClient.inContext(dataContext) {
                caller.ensureActive();if(generation!=readGeneration)throw CancellationException("Details read superseded")
                val wasDenied=vodDenial!=null
                applyBundle(bundle, watchedAtRead != watchedRevision, resumeAtRead != resumeRevision);vodDenial=null;denialFence=authorizationAttempt.sequence
                if(wasDenied) {libraryLookup=CompletableDeferred();libraryGeneration++;scope.launch {loadLibrary()}}
            }
        } catch(error:CancellationException) {throw error
        } catch(error:Exception) {caller.ensureActive();ProfileDataContext.requireCurrent(dataContext);if(generation==readGeneration)error.vodDenial?.let(::retireAccess);throw error}
    }

    private fun applyBundle(bundle: DetailsBundle, preserveWatched: Boolean = false, preserveResume: Boolean = false) {
        movieOptimism = null; episodeOptimism.clear()
        if (target.isMovie) {
            title = bundle.movieDetails ?: throw ApiError.NotReady("Details unavailable")
        } else {
            val details = bundle.seriesDetails ?: throw ApiError.NotReady("Details unavailable")
            title = details.title
            seasons = details.seasons
            orderings = details.availableOrderings
            activeOrdering = details.activeOrdering
            upNext = bundle.upNextEpisode
            primaryEpisode = findPrimaryEpisode(details.seasons, bundle.upNextEpisode)
            privacyEpisode = EpisodePrivacy.currentEpisode(details.seasons, bundle.upNextEpisode)
            if (selectedSeason == null) selectedSeason = primaryEpisode?.seasonNumber
        }
        if (!preserveResume) resume = bundle.resume
        trailers = bundle.trailers
        serverInWatchlist = bundle.inWatchlist
        if (!preserveWatched) movieWatched = bundle.watched
        similar = bundle.similar.orEmpty().distinctBy { it.id }
        if (!preserveWatched) watchedOverrides.clear()
        loadError = null
    }

    /** Only series with more than one ordering offer the choice. */
    val offersOrderings: Boolean get() = !target.isMovie && orderings.size > 1

    /** The ordering the page shows (the one picked this visit, else the server's). */
    val currentOrdering: SeriesOrdering?
        get() = (pickedOrdering ?: activeOrdering).let { type -> orderings.firstOrNull { it.type == type } }

    /**
     * Shows the page in [ordering] (asked for explicitly, so a failed save still applies for the
     * visit) and saves it for the profile. Season numbers mean something else in the new
     * ordering, so the season chips start again from Up Next's.
     */
    suspend fun pickOrdering(ordering: SeriesOrdering) {
        if (ordering.type == currentOrdering?.type) return
        pickedOrdering = ordering.type
        selectedSeason = null
        val tvdbId = title?.tvdbId ?: target.tvdbId?.toLongOrNull()
        if (tvdbId != null) runCatching { ApiClient.setSeriesOrdering(tvdbId, ordering.type) }
        load()
    }

    /** The top-billed cast; the server already caps the list. */
    val cast: List<CastMember> get() = title?.credits?.cast.orEmpty().distinctBy { it.id }

    fun isWatched(episode: Episode): Boolean = episodeOptimism[episode.id]?.takeIf { it.current() }?.selection?.watched ?: watchedOverrides[episode.id] ?: (episode.watched == true)

    /** Series: none / some / all aired regular-season episodes watched (the Expo rule). */
    val seriesWatchState: WatchState
        get() {
            val aired = seasons.filter { it.number > 0 }.flatMap { it.episodes }.filter(::hasAired)
            val watched = aired.count(::isWatched)
            return when {
                watched == 0 -> WatchState.NONE
                watched == aired.size -> WatchState.COMPLETE
                else -> WatchState.PARTIAL
            }
        }

    /** Capture the actual metadata selection and desired state before a job or confirmation. */
    fun movieWatchIntent(watched: Boolean): com.strmr.tv.api.WatchedIntent? {
        val original = title ?: return null
        val generation = readGeneration
        if (dataContext != ProfileDataContext.current || vodDenial != null) return null
        return com.strmr.tv.api.movieWatchedIntent(target, watched, dataContext) { readGeneration == generation && title === original && dataContext == ProfileDataContext.current && vodDenial == null && scope.coroutineContext[Job]?.isActive != false }
    }
    fun episodeWatchIntent(episodes: List<Episode>, watched: Boolean, watchedScope: WatchedScope?): com.strmr.tv.api.WatchedIntent? {
        val original = title ?: return null
        val generation = readGeneration
        val snapshot = episodes.toList()
        if (snapshot.isEmpty() || dataContext != ProfileDataContext.current || vodDenial != null || snapshot.any { ep -> seasons.none { season -> season.episodes.any { it === ep } } }) return null
        return com.strmr.tv.api.episodesWatchedIntent(target, snapshot, watched, watchedScope, dataContext) {
            readGeneration == generation && title === original && dataContext == ProfileDataContext.current && vodDenial == null && scope.coroutineContext[Job]?.isActive != false &&
                snapshot.all { ep -> seasons.any { season -> season.episodes.any { it === ep } } }
        }
    }

    suspend fun applyWatch(selection: WatchedSelection) = applyWatch(selection, ServerWatchedAccess)
    internal suspend fun applyWatch(selection: WatchedSelection, access: WatchedAccess) {
        require(selection.target === target) { "Watched title changed" }
        val optimism = WatchedOptimism(selection)
        try {
            performWatched(selection, access,
                start = {
                    if (target.isMovie) movieOptimism = optimism
                    else selection.episodes.forEach { episodeOptimism[it.id] = optimism }
                },
                success = {
                    watchedRevision++
                    if (target.isMovie) { movieWatchedBase = selection.watched; if (!selection.watched) { resume = null; resumeRevision++ } }
                    else selection.episodes.forEach { watchedOverrides[it.id] = selection.watched }
                    watchedError = null
                    com.strmr.tv.library.TitleActions.changed(selection.operation)
                },
                failure = { watchedError = "Could not update watched state. Try again." },
            )
        } finally {
            // Ending only this private transient projection cannot overwrite a replacement run.
            optimism.active = false
            selection.publish {
                if (movieOptimism === optimism) movieOptimism = null
                selection.episodes.forEach { if (episodeOptimism[it.id] === optimism) episodeOptimism.remove(it.id) }
            }
        }
    }

    /** UI ends a failed mutation here; it never retries or advances a denied compound operation. */
    suspend fun applyWatchForUi(selection: WatchedSelection): Boolean = try {
        applyWatch(selection)
        true
    } catch (error: CancellationException) { throw error
    } catch (error: Exception) {
        selection.publish { watchedError = error.vodDenial?.message ?: "Could not update watched state. Try again." }
        false
    }

    /** Where to resume, per the server's `resume.eligible` decision. */
    fun resumePoint(episode: Episode?): StartPoint? {
        if (episode == null) return resume?.startPoint
        episode.resume?.let { return it.startPoint }
        val next = upNext ?: return null
        return if (isUpNext(episode, next)) resume?.startPoint else null
    }

    /** How far through the resume point is (0–1), for the primary button's fill. */
    fun resumeFraction(episode: Episode?): Float {
        if (episode == null) return resume?.fraction ?: 0f
        episode.resume?.let { return it.fraction }
        val next = upNext ?: return 0f
        return if (isUpNext(episode, next)) resume?.fraction ?: 0f else 0f
    }

    /**
     * The episode / resume point the primary button plays, and its label. The resume time is the
     * button's focus caption and its fill, not part of the label.
     */
    data class Primary(val episode: Episode?, val start: StartPoint?, val label: String)

    val primary: Primary?
        get() {
            if(!playable)return null
            if (target.isMovie) {
                val at = resumePoint(null)
                return if (at != null) Primary(null, at, "Resume") else Primary(null, null, "Play")
            }
            val ep = primaryEpisode ?: return null
            val at = resumePoint(ep)
            return if (at != null) Primary(ep, at, "Resume ${ep.code}") else Primary(ep, null, "Play ${ep.code}")
        }

    /**
     * Normal Play uses the coordinator's source policy. The legacy server path can still
     * offer a library picker; explicit Versions and library pages remain deliberate choices.
     */
    private fun startup(entry: StartupEntry): PlaybackStartupIntent? {
        // Measurement cannot change whether the established playback path is allowed to run.
        if (PlaybackCoordinator.resolving.value != null) return null
        return runCatching {
            val generation = accessGeneration
            val owner = PlaybackSessionContext.capture(dataContext)
            (captureStartup?.invoke(owner, target) ?: PlaybackStartupTiming.intent(owner, target, entry,
                startupPreparation(PlaybackCoordinator.warm.value, target.titleId, dataContext, ApiClient.profileId))).also {
                it.bind(target, dataContext, scope.coroutineContext[Job]) {
                    startupCurrent() && generation == accessGeneration && playable
                }
            }
        }.getOrNull()
    }

    fun play(episode: Episode?, start: StartPoint?, picks: PrequeuePick? = null,
        startupIntent: PlaybackStartupIntent? = null, measureStartup: Boolean = true) {
        if (playJob?.isActive == true || !playable) { startupIntent?.notStarted(); return }
        val timing = startupIntent ?: if (measureStartup) startup(StartupEntry.DETAILS_PLAY) else null
        playJob = scope.launch {
            try {
                ApiClient.inContext(dataContext) {}
                val versions = if (libraryLookup.isCompleted) library else withTimeoutOrNull(8_000) { libraryLookup.await() }
                ApiClient.inContext(dataContext) {
                    if(!playable) { timing?.notStarted(); return@inContext }
                    PlaybackCoordinator.play(request(episode, start).copy(library = versions), picks, expectedContext = dataContext, startup = timing) {
                        ApiClient.inContext(dataContext) { playChoice = PlayChoice(episode, start, picks) }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                timing?.failed()
                if (dataContext == ProfileDataContext.current) AppToast.show("Source preferences did not respond. Try again.")
            } catch (e: CancellationException) { timing?.cancel(); throw e
            } catch (_: Exception) {
                timing?.failed()
                if (dataContext == ProfileDataContext.current) AppToast.show("Source preferences could not be checked. Try again.")
            }
        }
        playJob?.invokeOnCompletion { timing?.cancel() }
    }

    private inline fun startupAction(entry: StartupEntry, action: (PlaybackStartupIntent?) -> Unit) {
        val timing = startup(entry)
        try { action(timing) }
        catch (error: CancellationException) { timing?.cancel(); throw error }
        catch (error: Exception) { timing?.failed(); throw error }
    }

    /** Stream Instead keeps search intent for this title and the episodes after it. */
    fun streamInstead(choice: PlayChoice) {
        if(playable)startupAction(StartupEntry.STREAM_INSTEAD) { timing ->
            PlaybackCoordinator.play(request(choice.episode, choice.start).copy(sourceSelection = PlaybackRequest.SourceSelection.SEARCH_ONLY), choice.picks, expectedContext = dataContext, startup = timing)
        }
    }

    /** A copy picked in Versions. */
    fun playVersion(version: LibraryVersion, episode: Episode?, start: StartPoint?) {
        if(playable)startupAction(StartupEntry.LIBRARY_VERSION) { timing ->
            PlaybackCoordinator.playLibrary(request(episode, start), version, expectedContext = dataContext, startup = timing)
        }
    }

    fun play(source: SourceResult, episode: Episode?, start: StartPoint?, manual: ManualSourceTracks? = null) {
        if(playable)startupAction(StartupEntry.MANUAL_SOURCE) { timing ->
            PlaybackCoordinator.play(request(episode, start), source, manual, expectedContext = dataContext, startup = timing)
        }
    }

    fun request(episode: Episode?, start: StartPoint?): PlaybackRequest {
        val primaryBackdrop = displayBackdropUrl
        val alternates = title?.backdrops?.map { it.url }.orEmpty()
        val art = (listOfNotNull(primaryBackdrop) + alternates).distinct().shuffled(Random(artSeed))
        return PlaybackRequest(
            title = target,
            episode = episode?.let(::episodeInfo),
            start = start,
            backdrops = listOfNotNull(episode?.image?.url) + art,
            logoUrl = title?.logo?.url ?: target.logoUrl,
            following = episode?.let { airedEpisodes(it) }.orEmpty(),
            episodeOrderKnown = episode != null && seasons.any { season -> season.number > 0 && season.episodes.any { it.id == episode.id } },
            library = library,
        )
    }

    private fun episodeInfo(e: Episode) = PlaybackRequest.EpisodeInfo(
        e.seasonNumber, e.episodeNumber, e.absoluteEpisodeNumber, e.name, e.itemId, e.image?.url, e.overview, e.numbering, e.id,
    )

    /** The aired regular-season episodes after `after`, for Up Next. */
    private fun airedEpisodes(after: Episode): List<PlaybackRequest.EpisodeInfo> {
        val regular = seasons.filter { it.number > 0 }.sortedBy { it.number }.flatMap { it.episodes }
        val index = regular.indexOfFirst { it.id == after.id }
        if (index < 0) return emptyList()
        return regular.drop(index + 1).takeWhile(::hasAired).map(::episodeInfo)
    }

    /** The title's release badge, else (series) whether the episode Play targets has aired. */
    val headerBadge: CardBadge?
        get() {
            title?.let { t -> ReleaseBadge.forTitle(t.availability, t.status, t.releaseDate)?.let { return it } }
            if (target.isMovie) return null
            return primaryEpisode?.let { ReleaseBadge.forEpisode(it.airedDate) }?.let { CardBadge(it) }
        }

    val metadataLine: String
        get() {
            val parts = mutableListOf<String>()
            (title?.year ?: target.year)?.let { parts += it.toString() }
            title?.certification?.takeIf { it.isNotEmpty() }?.let { parts += it }
            formatRuntime(title?.runtimeMinutes)?.let { parts += it }
            if (seasons.isNotEmpty()) {
                val n = seasons.count { it.number > 0 }
                parts += if (n == 1) "1 Season" else "$n Seasons"
            }
            title?.genres?.takeIf { it.isNotEmpty() }?.let { parts += it.take(3).joinToString(", ") }
            return parts.joinToString("  ·  ")
        }

    enum class WatchState { NONE, PARTIAL, COMPLETE }

    companion object {
        /** Aired: no upcoming-release badge (only aired episodes are marked watched in bulk). */
        fun hasAired(episode: Episode): Boolean = ReleaseBadge.forEpisode(episode.airedDate) == null

        fun findPrimaryEpisode(seasons: List<Season>, upNext: EpisodeRef?): Episode? {
            if (upNext != null) {
                for (season in seasons) season.episodes.firstOrNull { isUpNext(it, upNext) }?.let { return it }
            }
            val first = seasons.firstOrNull { it.number > 0 } ?: seasons.firstOrNull()
            return first?.episodes?.firstOrNull()
        }

        fun isUpNext(episode: Episode, ref: EpisodeRef): Boolean {
            val a = episode.itemId
            val b = ref.itemId
            if (a != null && b != null) return a == b
            return episode.seasonNumber == ref.seasonNumber && episode.episodeNumber == ref.episodeNumber
        }
    }
}
