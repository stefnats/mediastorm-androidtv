package com.strmr.tv.ui.reminders

import com.strmr.tv.api.*
import com.strmr.tv.app.AppSession
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.ui.details.contentLocalScopes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val ReminderPassiveExplanation = "Updates when this title is checked in the app."

data class ReminderTarget(val title: DetailsTarget, val episode: Episode? = null)
data class ReminderChoice(val contentId: String, val contentType: String, val eventKind: String, val label: String, val releaseType: String? = null, val episode: ReminderEpisode? = null) {
    fun body() = reminderBody(contentId, contentType, eventKind, releaseType, episode)
    fun matches(value: Reminder, requestedContentId: String?) = requestedContentId==contentId &&
        reminderDetailsTarget(value.contentId,value.contentType,value.title)!=null && value.contentType == contentType &&
        value.eventKind == eventKind && value.releaseType == releaseType && value.episode == episode && value.createdOnCurrentDevice==true
}

fun remindersAllowed(profile: Profile?) = profile != null && profile.isKidsProfile != true

/** Numbering belongs to the delivered episode, independently of the catalogue provider. */
fun officialReminderEpisode(episode: Episode): Boolean {
    val numbering=episode.numbering ?: return false
    return Regex("(?:tmdb:(?:series|tv):[1-9][0-9]*|tvdb:(?:series:)?[1-9][0-9]*)").matches(numbering.seriesId) &&
        numbering.ordering.orEmpty() in setOf("","official") && episode.id.isNotBlank() && episode.id.length<=256 &&
        episode.seasonNumber in 0..10_000 && episode.episodeNumber in 1..100_000
}

/** Only the exact official numbering can subscribe to an episode source observation. */
fun reminderChoices(target: ReminderTarget): List<ReminderChoice> {
    val title = target.title
    if(contentLocalScopes(title).isEmpty()) return emptyList()
    val id = title.titleId.trim()
    if (id.length !in 1..128) return emptyList()
    if (title.isMovie) return listOf(
        ReminderChoice(id,"movie","release","Announced digital release date", "digital"),
        ReminderChoice(id,"movie","release","Announced physical release date", "physical"),
        ReminderChoice(id,"movie","release","Announced theatrical release date", "theatrical"),
        ReminderChoice(id,"movie","matching_source","A matching source candidate"),
    )
    val choices = mutableListOf(ReminderChoice(id,"series","new_episode","New episode metadata for this series"))
    target.episode?.takeIf(::officialReminderEpisode)?.let { ep ->
        choices += ReminderChoice(id,"series","matching_source","A matching source candidate for ${ep.code}", episode=ReminderEpisode(ep.id,EpisodeNumbering(ep.numbering!!.seriesId,"official"),ep.seasonNumber,ep.episodeNumber))
    }
    return choices
}

fun reminderDescription(kind: String, releaseType: String?, episode: ReminderEpisode? = null): String = when(kind) {
    "release" -> "Announced ${releaseType?.takeIf { it in setOf("digital","physical","theatrical") }?.let { "$it " }.orEmpty()}release date. This does not confirm playback availability."
    "new_episode" -> "New episode metadata was observed. A playable stream is not confirmed."
    "matching_source" -> "${episode?.let { "S${it.seasonNumber}E${it.episodeNumber}: " }.orEmpty()}A matching source candidate, not a playback guarantee."
    else -> "An update was recorded for this title."
}

fun reminderDeviceLabel(reminder: Reminder): String? = if(reminder.eventKind!="matching_source") null else when(reminder.createdOnCurrentDevice) {
    true -> "Source checks for this device"
    false -> "Source checks for another device"
    null -> "Device-specific source checks"
}

/** A returned server title must still describe the same loaded reminder row. */
fun reminderDetailsTarget(contentId: String, contentType: String, title: Title): DetailsTarget? {
    if(contentId!=title.id || contentType!=title.mediaType || contentType !in setOf("movie","series") || title.name.isBlank()) return null
    return DetailsTarget.from(title).takeIf { contentLocalScopes(it).isNotEmpty() && contentId.length<=128 }
}

fun reminderRecordedLabel(value: String?, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String? =
    value?.takeIf {it.length in 1..64}?.let {raw -> runCatching {
        "Recorded " + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone).format(Instant.parse(raw))
    }.getOrNull() }

interface ReminderGateway {
    suspend fun list(expected: Long): ReminderList
    suspend fun inbox(expected: Long, unread: Boolean, cursor: String?): ReminderInbox
    suspend fun create(choice: ReminderChoice, expected: Long): ReminderCreated
    suspend fun revoke(id: String, expected: Long)
    suspend fun update(id: String, read: Boolean?, dismissed: Boolean?, expected: Long): ReminderEventUpdated
}
class ServerReminderGateway : ReminderGateway {
    override suspend fun list(expected: Long) = ApiClient.reminders(expected)
    override suspend fun inbox(expected: Long, unread: Boolean, cursor: String?) = ApiClient.reminderInbox(expected, unread, cursor)
    override suspend fun create(choice: ReminderChoice, expected: Long) = ApiClient.createReminder(choice.body(), expected)
    override suspend fun revoke(id: String, expected: Long) = ApiClient.revokeReminder(id, expected)
    override suspend fun update(id: String, read: Boolean?, dismissed: Boolean?, expected: Long) = ApiClient.updateReminderEvent(id, reminderEventBody(read,dismissed), expected)
}

enum class ReminderRecovery { REFRESH, RELOAD_TITLE, MANAGE_REMINDERS }

data class RemindersState(
    val reminders: List<Reminder> = emptyList(), val events: List<ReminderEvent> = emptyList(),
    val supported: Boolean = false, val loading: Boolean = true, val busy: Boolean = false,
    val unreadOnly: Boolean = false, val nextCursor: String? = null, val error: String? = null, val closed: Boolean = false,
    val recovery: ReminderRecovery = ReminderRecovery.REFRESH,
)

/** One page/dialog owns its identity, capability confirmation and pending requests. */
class ReminderController(
    private val scope: CoroutineScope,
    private val gateway: ReminderGateway = ServerReminderGateway(),
    private val contexts: StateFlow<Long> = ProfileDataContext.changes,
    private val adult: () -> Boolean = { remindersAllowed(AppSession.profile.value) },
    private val expected: Long = contexts.value,
) {
    private val value = MutableStateFlow(RemindersState())
    val state: StateFlow<RemindersState> = value
    private var task: Job? = null
    private var changed: Job? = null
    init { changed = scope.launch { contexts.first { it != expected }; close() } }
    private val cursors = mutableSetOf<String>()
    private fun current() = !value.value.closed && contexts.value == expected && adult()
    private fun available(): Boolean { if (!current()) close(); return current() }
    private fun fail(message: String, recovery: ReminderRecovery = ReminderRecovery.REFRESH) { if (current()) value.value=RemindersState(unreadOnly=value.value.unreadOnly,loading=false,error=message,recovery=recovery) }
    private fun mutationFailure(error: Exception) {
        when((error as? ApiError)?.code) {
            "reminder_metadata_reload_required" -> fail("Reload this title's metadata, then open Remind me again.",ReminderRecovery.RELOAD_TITLE)
            "reminder_interest_limit" -> fail("Stop active reminders or dismiss old inbox updates before adding another.",ReminderRecovery.MANAGE_REMINDERS)
            else -> fail("The change could not be confirmed. Refresh before trying again.")
        }
    }

    fun load(withInbox: Boolean = true) {
        if (!available() || task?.isActive == true) return
        value.value=value.value.copy(loading=true,supported=false,error=null)
        task=scope.launch {
            try {
                val reminders=gateway.list(expected)
                if (!current()) return@launch
                if (!reminders.remindersSupported) { fail("Update the server to use reminders and inbox."); return@launch }
                val inbox=if(withInbox) gateway.inbox(expected,value.value.unreadOnly,null) else null
                if (!current()) return@launch
                if(inbox!=null && !inbox.remindersSupported) { fail("The server did not confirm inbox support."); return@launch }
                cursors.clear()
                value.value=value.value.copy(loading=false,supported=true,reminders=reminders.items.filter { validId(it.id) }.distinctBy {it.id},events=inbox?.items.orEmpty().filter { validId(it.id) && it.dismissedAt==null && (!value.value.unreadOnly || it.readAt==null) }.distinctBy {it.id},nextCursor=inbox?.nextCursor?.takeIf(::validCursor))
            } catch(e: CancellationException) { throw e
            } catch(e: Exception) { fail(if(e is ApiError && e.statusCode in setOf(404,405,501)) "Update the server to use reminders and inbox." else "Reminders could not be loaded. Refresh to try again.") }
        }
    }

    fun filterUnread(unread: Boolean) {
        if (!available() || task?.isActive==true || unread==value.value.unreadOnly) return
        value.value=value.value.copy(unreadOnly=unread,events=emptyList(),nextCursor=null)
        load()
    }
    fun more() {
        val cursor=value.value.nextCursor ?: return
        if (!available() || !value.value.supported || task?.isActive==true || !cursors.add(cursor)) return
        value.value=value.value.copy(busy=true,error=null)
        task=scope.launch {
            try {
                val reply=gateway.inbox(expected,value.value.unreadOnly,cursor)
                if(!current()) return@launch
                if(!reply.remindersSupported) { fail("The server did not confirm inbox support."); return@launch }
                value.value=value.value.copy(busy=false,events=(value.value.events+reply.items).filter {validId(it.id)&&it.dismissedAt==null && (!value.value.unreadOnly || it.readAt==null)}.distinctBy {it.id},nextCursor=reply.nextCursor?.takeIf {validCursor(it)&&it !in cursors})
            } catch(e: CancellationException) { throw e
            } catch(_: Exception) { if(current()) { cursors.remove(cursor); value.value=value.value.copy(busy=false,error="More updates could not be loaded. Try again.") } }
        }
    }
    fun create(choice: ReminderChoice, done: () -> Unit) = mutate {
        val reply=gateway.create(choice,expected)
        if(!current()) return@mutate
        val saved=reply.item
        if(!reply.remindersSupported || saved==null || !validId(saved.id) || !choice.matches(saved,reply.requestedContentId)) { fail("The server did not confirm this reminder. Refresh before trying again."); return@mutate }
        value.value=value.value.copy(busy=false,reminders=value.value.reminders.filterNot {it.id==saved.id}+saved)
        done()
    }
    fun revoke(reminder: Reminder) {
        if(value.value.reminders.none {it.id==reminder.id}) return
        mutate {
            gateway.revoke(reminder.id,expected)
            if(current()) value.value=value.value.copy(busy=false,reminders=value.value.reminders.filterNot {it.id==reminder.id})
        }
    }
    fun update(event: ReminderEvent, read: Boolean? = null, dismissed: Boolean? = null) {
        if(value.value.events.none {it.id==event.id} || read==null && dismissed==null) return
        mutate {
            val reply=gateway.update(event.id,read,dismissed,expected)
            if(!current()) return@mutate
            val saved=reply.item
            if(!reply.remindersSupported || saved?.id!=event.id || read!=null && (saved.readAt!=null)!=read || dismissed!=null && (saved.dismissedAt!=null)!=dismissed) { fail("The server did not confirm this inbox change. Refresh before trying again."); return@mutate }
            value.value=value.value.copy(busy=false,events=value.value.events.map {if(it.id==event.id)saved else it}.filter {it.dismissedAt==null && (!value.value.unreadOnly || it.readAt==null)})
        }
    }
    fun openReminder(reminder: Reminder): DetailsTarget? {
        if(!available() || !value.value.supported || value.value.loading || value.value.busy) return null
        val stored=value.value.reminders.singleOrNull {it.id==reminder.id} ?: return null
        return reminderDetailsTarget(stored.contentId,stored.contentType,stored.title)
    }
    fun openEvent(event: ReminderEvent): DetailsTarget? {
        if(!available() || !value.value.supported || value.value.loading || value.value.busy) return null
        val stored=value.value.events.singleOrNull {it.id==event.id} ?: return null
        return reminderDetailsTarget(stored.contentId,stored.contentType,stored.title)
    }
    fun reloadTitle(reload: suspend () -> Unit, done: () -> Unit) {
        if(!available() || task?.isActive==true || value.value.recovery!=ReminderRecovery.RELOAD_TITLE) return
        value.value=value.value.copy(loading=true,busy=true,error=null)
        task=scope.launch {
            try { reload(); if(current()) { done(); close() } }
            catch(error: CancellationException) {throw error}
            catch(_: Exception) {fail("Title metadata could not be reloaded. Try Reload title again.",ReminderRecovery.RELOAD_TITLE)}
        }
    }
    fun manageReminders(open: () -> Unit) {
        if(!available() || task?.isActive==true || value.value.recovery!=ReminderRecovery.MANAGE_REMINDERS) return
        open(); close()
    }
    private fun mutate(action: suspend () -> Unit) {
        if(!available() || !value.value.supported || value.value.loading || task?.isActive==true) return
        value.value=value.value.copy(busy=true,error=null)
        task=scope.launch {
            try { action() }
            catch(e: CancellationException) { throw e }
            catch(error: Exception) { mutationFailure(error) }
        }
    }
    fun close() { value.value=RemindersState(loading=false,closed=true); task?.cancel(); changed?.cancel() }
    private fun validId(id: String)=id.length in 1..128 && id.isNotBlank()
    private fun validCursor(cursor: String)=cursor.length in 1..2048 && cursor.isNotBlank()
}
