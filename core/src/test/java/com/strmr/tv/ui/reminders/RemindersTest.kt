package com.strmr.tv.ui.reminders

import com.strmr.tv.api.*
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemindersTest {
    private val movie=DetailsTarget("tmdb:movie:123","Synthetic movie",true)
    private val series=DetailsTarget("tmdb:series:41","Synthetic series",false)
    private val episode=Episode("episode-3",seasonNumber=2,episodeNumber=3,numbering=EpisodeNumbering(series.titleId,"official"))
    private val choice=reminderChoices(ReminderTarget(movie)).last()
    private fun reminder(option: ReminderChoice=choice,id: String="reminder-1")=Reminder(id,option.contentId,option.contentType,option.eventKind,Title(option.contentId,"Synthetic",mediaType=option.contentType),option.releaseType,option.episode,createdOnCurrentDevice=true)
    private fun event(id: String="event-1")=ReminderEvent(id,"reminder-1","matching_source",movie.titleId,"movie",Title(movie.titleId,"Synthetic",mediaType="movie"),candidateOnly=true)

    private inner class Gateway : ReminderGateway {
        val reads=mutableListOf<Long>()
        val pages=mutableListOf<Triple<Long,Boolean,String?>>()
        val creates=mutableListOf<Pair<ReminderChoice,Long>>()
        val revokes=mutableListOf<Pair<String,Long>>()
        val updates=mutableListOf<Triple<String,Pair<Boolean?,Boolean?>,Long>>()
        var list: suspend ()->ReminderList = {ReminderList(true,"observations_only",listOf(reminder()))}
        var inbox: suspend (Boolean,String?)->ReminderInbox = {_,_->ReminderInbox(true,"observations_only",listOf(event()))}
        var create: suspend (ReminderChoice)->ReminderCreated = {ReminderCreated(true,"observations_only",reminder(it,"created"),requestedContentId=it.contentId)}
        var revoke: suspend ()->Unit = {}
        var update: suspend (String,Boolean?,Boolean?)->ReminderEventUpdated = {id,read,dismissed->ReminderEventUpdated(true,event(id).copy(readAt=if(read==true)"2026-11-15T12:00:00Z" else null,dismissedAt=if(dismissed==true)"2026-11-15T12:00:00Z" else null))}
        override suspend fun list(expected: Long)=list().also {reads+=expected}
        override suspend fun inbox(expected: Long,unread: Boolean,cursor: String?)=inbox(unread,cursor).also {pages+=Triple(expected,unread,cursor)}
        override suspend fun create(choice: ReminderChoice,expected: Long): ReminderCreated {creates+=choice to expected;return create(choice)}
        override suspend fun revoke(id: String,expected: Long) {revokes+=id to expected;revoke()}
        override suspend fun update(id: String,read: Boolean?,dismissed: Boolean?,expected: Long): ReminderEventUpdated {updates+=Triple(id,read to dismissed,expected);return update(id,read,dismissed)}
    }
    private inner class Fixture(scope: TestScope) {
        val contexts=MutableStateFlow(7L)
        val gateway=Gateway()
        var adult=true
        var notices=0
        val controller=ReminderController(scope.backgroundScope,gateway,contexts,{adult})
    }

    @Test fun `movie actions separate announced milestones from source candidates`() {
        val choices=reminderChoices(ReminderTarget(movie))
        assertEquals(listOf("digital","physical","theatrical"),choices.filter {it.eventKind=="release"}.map {it.releaseType})
        assertEquals(1,choices.count {it.eventKind=="matching_source"})
        assertTrue(choices.all {it.episode==null && it.contentType=="movie"})
    }
    @Test fun `real series title and official episode actions preserve their exact namespace`() {
        for(id in listOf("tmdb:series:41","tvdb:series:74852","tmdb:tv:41","tvdb:74852","catalog:CaseSensitive-Alias")) {
            val title=series.copy(titleId=id)
            val ep=episode.copy(numbering=EpisodeNumbering(id,"official"))
            val choices=reminderChoices(ReminderTarget(title,ep))
            assertEquals(id,choices.first().contentId)
            assertEquals("new_episode",choices.first().eventKind)
            if(id.startsWith("catalog:")) assertEquals(1,choices.size) else {
                assertEquals(2,choices.size)
                assertEquals(id,choices.last().episode!!.numbering.seriesId)
            }
            assertEquals(listOf("new_episode"),reminderChoices(ReminderTarget(title)).map {it.eventKind})
        }
    }
    @Test fun `alternate unknown owner and invalid episodes never subscribe to a whole series source`() {
        for(ep in listOf(episode.copy(numbering=null),episode.copy(numbering=EpisodeNumbering(series.titleId,"dvd")),episode.copy(numbering=EpisodeNumbering("unknown-owner","official")),episode.copy(numbering=EpisodeNumbering("imdb:tt123","official")),episode.copy(numbering=EpisodeNumbering("tt123","official")),episode.copy(id=""),episode.copy(episodeNumber=0),episode.copy(seasonNumber=10_001))) {
            assertEquals(listOf("new_episode"),reminderChoices(ReminderTarget(series,ep)).map {it.eventKind})
        }
        assertTrue(reminderChoices(ReminderTarget(movie.copy(isMovie=false))).isEmpty())
        assertTrue(reminderChoices(ReminderTarget(series.copy(titleId="unmatched",fromLibrary=true))).isEmpty())
    }
    @Test fun `a delivered official cross provider episode keeps its exact numbering owner`() {
        val cross=episode.copy(numbering=EpisodeNumbering("tvdb:series:74852","official"))
        val source=reminderChoices(ReminderTarget(series,cross)).last()
        assertEquals("matching_source",source.eventKind)
        assertEquals(series.titleId,source.contentId)
        assertEquals("tvdb:series:74852",source.episode!!.numbering.seriesId)
        assertEquals(cross.id,source.episode.episodeId)
        assertEquals("tvdb:series:74852",source.body()["episode"]!!.jsonObject["numbering"]!!.jsonObject["seriesId"]!!.jsonPrimitive.content)
    }
    @Test fun `specials retain season zero and source payload excludes metadata and credentials`() {
        val special=episode.copy(seasonNumber=0,episodeNumber=1)
        val body=reminderChoices(ReminderTarget(series,special)).last().body()
        assertEquals(setOf("contentId","contentType","eventKind","episode"),body.keys)
        val ep=body["episode"]!!.jsonObject
        assertEquals(setOf("episodeId","numbering","seasonNumber","episodeNumber"),ep.keys)
        assertEquals(0,ep["seasonNumber"]!!.jsonPrimitive.int)
        assertEquals(setOf("seriesId","ordering"),ep["numbering"]!!.jsonObject.keys)
        assertEquals("official",ep["numbering"]!!.jsonObject["ordering"]!!.jsonPrimitive.content)
    }
    @Test fun `event patch preserves explicit false and omits untouched fields`() {
        assertEquals("""{"read":false}""",reminderEventBody(read=false).toString())
        assertEquals(setOf("dismissed"),reminderEventBody(dismissed=true).keys)
        assertFalse(reminderEventBody(dismissed=false)["dismissed"]!!.jsonPrimitive.boolean)
    }
    @Test fun `children and unknown profiles have no reminder access`()=runTest {
        assertFalse(remindersAllowed(null));assertFalse(remindersAllowed(Profile("child","Child",isKidsProfile=true)))
        assertTrue(remindersAllowed(Profile("adult","Adult",isKidsProfile=false)))
        val f=Fixture(this);f.adult=false;f.controller.load();f.controller.create(choice) {f.notices++};runCurrent()
        assertTrue(f.gateway.reads.isEmpty());assertTrue(f.gateway.creates.isEmpty());assertTrue(f.controller.state.value.closed)
    }
    @Test fun `no mutation is allowed before a current capability read`()=runTest {
        val f=Fixture(this);f.controller.create(choice) {f.notices++};f.controller.revoke(reminder());f.controller.update(event(),read=true);runCurrent()
        assertTrue(f.gateway.creates.isEmpty());assertTrue(f.gateway.revokes.isEmpty());assertTrue(f.gateway.updates.isEmpty())
    }
    @Test fun `old server marker absence prevents writes without a legacy fallback`()=runTest {
        assertFalse(ApiJson.decodeFromString<ReminderList>("{}").remindersSupported)
        val f=Fixture(this);f.gateway.list={ReminderList()};f.controller.load();runCurrent();f.controller.create(choice) {f.notices++};runCurrent()
        assertFalse(f.controller.state.value.supported);assertTrue(f.gateway.creates.isEmpty());assertTrue(f.gateway.pages.isEmpty())
    }
    @Test fun `an inbox capability mismatch clears lists and prevents stale mutation`()=runTest {
        val f=Fixture(this);f.gateway.inbox={_,_->ReminderInbox()};f.controller.load();runCurrent()
        assertFalse(f.controller.state.value.supported);assertTrue(f.controller.state.value.reminders.isEmpty());assertTrue(f.controller.state.value.events.isEmpty())
    }
    @Test fun `editor capability load does not read or poll the inbox`()=runTest {
        val f=Fixture(this);f.controller.load(withInbox=false);runCurrent()
        assertTrue(f.controller.state.value.supported);assertEquals(listOf(7L),f.gateway.reads);assertTrue(f.gateway.pages.isEmpty())
    }
    @Test fun `creating a reminder sends the original identity once and requires matching server confirmation`()=runTest {
        val f=Fixture(this);val reply=CompletableDeferred<ReminderCreated>();f.gateway.create={reply.await()}
        f.controller.load(false);runCurrent();f.controller.create(choice) {f.notices++};f.controller.create(choice) {f.notices++};runCurrent()
        assertEquals(listOf(choice to 7L),f.gateway.creates)
        reply.complete(ReminderCreated(true,"observations_only",reminder(choice,"created"),requestedContentId=choice.contentId));runCurrent()
        assertEquals(1,f.notices);assertFalse(f.controller.state.value.busy)
    }
    @Test fun `wrong title or another device create response is not treated as a confirmed reminder`()=runTest {
        for(wrong in listOf(reminder().copy(contentId="tmdb:movie:999"),reminder().copy(createdOnCurrentDevice=false))) {
            val f=Fixture(this);f.gateway.create={ReminderCreated(true,item=wrong,requestedContentId=choice.contentId)};f.controller.load(false);runCurrent();f.controller.create(choice) {f.notices++};runCurrent()
            assertEquals(0,f.notices);assertFalse(f.controller.state.value.supported)
            f.controller.create(choice) {f.notices++};runCurrent();assertEquals(1,f.gateway.creates.size)
        }
    }
    @Test fun `a candidate already known at creation never becomes an optimistic inbox event`()=runTest {
        val f=Fixture(this);f.gateway.create={ReminderCreated(true,item=reminder(it),currentCandidate=SourceAvailability("found"),requestedContentId=it.contentId)}
        f.controller.load(false);runCurrent();f.controller.create(choice) {};runCurrent()
        assertTrue(f.controller.state.value.events.isEmpty());assertTrue(f.controller.state.value.supported)
    }
    @Test fun `verified alias correlation accepts stable stored identity without guessing other titles`()=runTest {
        for(id in listOf("tmdb:series:41","tvdb:series:74852","tmdb:tv:41","catalog:CaseSensitive-Alias")) {
            val requested=reminderChoices(ReminderTarget(series.copy(titleId=id))).single()
            val stable=requested.copy(contentId="tmdb:series:41")
            val f=Fixture(this);f.gateway.create={ReminderCreated(true,item=reminder(stable),requestedContentId=id)}
            f.controller.load(false);runCurrent();f.controller.create(requested) {f.notices++};runCurrent()
            assertEquals(1,f.notices);assertEquals("tmdb:series:41",f.controller.state.value.reminders.last().contentId)
        }
        val f=Fixture(this);f.gateway.create={ReminderCreated(true,item=reminder(),requestedContentId="tmdb:movie:999")}
        f.controller.load(false);runCurrent();f.controller.create(choice) {f.notices++};runCurrent();assertEquals(0,f.notices)
    }
    @Test fun `alias correlation never permits changed official episode coordinates`()=runTest {
        val requested=reminderChoices(ReminderTarget(series,episode)).last()
        val f=Fixture(this);f.gateway.create={ReminderCreated(true,item=reminder(requested.copy(episode=requested.episode!!.copy(episodeNumber=99))),requestedContentId=requested.contentId)}
        f.controller.load(false);runCurrent();f.controller.create(requested) {f.notices++};runCurrent();assertEquals(0,f.notices)
    }
    @Test fun `revoking a reminder retains existing inbox history`()=runTest {
        val f=Fixture(this);f.controller.load();runCurrent();f.controller.revoke(reminder());runCurrent()
        assertEquals(listOf("reminder-1" to 7L),f.gateway.revokes);assertTrue(f.controller.state.value.reminders.isEmpty());assertEquals(1,f.controller.state.value.events.size)
    }
    @Test fun `read unread and dismiss actions only adopt confirmed server receipts`()=runTest {
        val f=Fixture(this);f.controller.load();runCurrent();f.controller.update(event(),read=true);runCurrent()
        assertNotNull(f.controller.state.value.events.single().readAt)
        f.controller.update(f.controller.state.value.events.single(),read=false);runCurrent();assertNull(f.controller.state.value.events.single().readAt)
        f.controller.update(event(),dismissed=true);runCurrent();assertTrue(f.controller.state.value.events.isEmpty())
        assertEquals(listOf(true to null,false to null,null to true),f.gateway.updates.map {it.second})
    }
    @Test fun `wrong or unconfirmed event receipts cannot alter the list optimistically`()=runTest {
        val f=Fixture(this);f.gateway.update={_,_,_->ReminderEventUpdated(true,event("wrong-event"))}
        f.controller.load();runCurrent();f.controller.update(event(),read=true);runCurrent()
        assertFalse(f.controller.state.value.supported);assertTrue(f.controller.state.value.events.isEmpty())
    }
    @Test fun `pagination retains profile filter and stops duplicate cursors without extra polling`()=runTest {
        val f=Fixture(this)
        f.gateway.inbox={_,cursor->if(cursor==null)ReminderInbox(true,items=listOf(event()),nextCursor="cursor-2") else ReminderInbox(true,items=listOf(event(),event("event-2")),nextCursor="cursor-2")}
        f.controller.load();runCurrent();f.controller.more();f.controller.more();runCurrent()
        assertEquals(listOf("event-1","event-2"),f.controller.state.value.events.map {it.id});assertNull(f.controller.state.value.nextCursor)
        f.controller.filterUnread(true);runCurrent()
        assertEquals(Triple(7L,true,null),f.gateway.pages.last())
    }
    @Test fun `duplicate and dismissed rows cannot crash inbox keys or escape the unread filter`()=runTest {
        val f=Fixture(this);f.gateway.list={ReminderList(true,items=listOf(reminder(),reminder()))}
        f.gateway.inbox={_,_->ReminderInbox(true,items=listOf(event(),event(),event("read").copy(readAt="2026-11-15T12:00:00Z"),event("hidden").copy(dismissedAt="2026-11-15T12:00:00Z")))}
        f.controller.load();runCurrent();assertEquals(1,f.controller.state.value.reminders.size);assertEquals(2,f.controller.state.value.events.size)
        f.controller.filterUnread(true);runCurrent();assertEquals(listOf("event-1"),f.controller.state.value.events.map {it.id})
    }
    @Test fun `late read replies cannot repopulate a replacement profile account or server`()=runTest {
        for(label in listOf("profile","account","server")) {
            val f=Fixture(this);val reply=CompletableDeferred<ReminderList>();f.gateway.list={withContext(NonCancellable) {reply.await()}}
            f.controller.load();runCurrent();f.contexts.value++;runCurrent();reply.complete(ReminderList(true,items=listOf(reminder())));runCurrent()
            assertTrue(label,f.controller.state.value.closed);assertTrue(label,f.controller.state.value.reminders.isEmpty())
        }
    }
    @Test fun `late create completion cannot notify or mutate a replacement context`()=runTest {
        val f=Fixture(this);val reply=CompletableDeferred<ReminderCreated>();f.gateway.create={withContext(NonCancellable) {reply.await()}}
        f.controller.load(false);runCurrent();f.controller.create(choice) {f.notices++};runCurrent();f.contexts.value++;runCurrent()
        reply.complete(ReminderCreated(true,item=reminder()));runCurrent();assertEquals(0,f.notices);assertTrue(f.controller.state.value.closed)
    }
    @Test fun `page disposal and becoming a child prevent pending and repeated actions`()=runTest {
        val f=Fixture(this);f.controller.load();runCurrent();f.adult=false;f.controller.revoke(reminder());f.controller.update(event(),read=true);runCurrent()
        assertTrue(f.gateway.revokes.isEmpty());assertTrue(f.gateway.updates.isEmpty());assertTrue(f.controller.state.value.closed)
        f.adult=true;f.controller.load();runCurrent();assertEquals(1,f.gateway.reads.size)
    }
    @Test fun `an old page cannot target the current profile even before its watcher runs`()=runTest {
        val gateway=Gateway();val contexts=MutableStateFlow(8L)
        val controller=ReminderController(backgroundScope,gateway,contexts,{true},expected=7L)
        controller.load();controller.create(choice) {};runCurrent();assertTrue(gateway.reads.isEmpty());assertTrue(controller.state.value.closed)
    }
    @Test fun `transport failures remain generic and never automatically replay writes`()=runTest {
        val f=Fixture(this);f.gateway.create={throw IOException("synthetic-private-password")}
        f.controller.load(false);runCurrent();f.controller.create(choice) {};runCurrent();f.controller.create(choice) {};runCurrent()
        assertEquals(1,f.gateway.creates.size);assertFalse(f.controller.state.value.error.orEmpty().contains("synthetic-private"))
    }
    @Test fun `metadata reload recovery actually reloads the title before another creation`()=runTest {
        val f=Fixture(this);var delivered=false
        f.gateway.create={if(!delivered) throw ApiError.Http(HttpFailure(409,"""{"code":"reminder_metadata_reload_required","error":"synthetic-private-detail"}""")) else ReminderCreated(true,item=reminder(it),requestedContentId=it.contentId)}
        f.controller.load(false);runCurrent();f.controller.create(choice) {};runCurrent()
        assertEquals(ReminderRecovery.RELOAD_TITLE,f.controller.state.value.recovery)
        assertFalse(f.controller.state.value.error.orEmpty().contains("synthetic-private"))
        f.controller.load(false);runCurrent();f.controller.create(choice) {};runCurrent()
        assertEquals(ReminderRecovery.RELOAD_TITLE,f.controller.state.value.recovery)
        var reloaded=0;f.controller.reloadTitle({delivered=true;reloaded++}) {};runCurrent();assertEquals(1,reloaded)
        val next=ReminderController(backgroundScope,f.gateway,f.contexts,{true});next.load(false);runCurrent();next.create(choice) {f.notices++};runCurrent();assertEquals(1,f.notices)
    }
    @Test fun `interest limit recovery requires stopping an existing interest rather than refreshing unchanged data`()=runTest {
        val f=Fixture(this);var limited=true
        f.gateway.create={if(limited) throw ApiError.Http(HttpFailure(409,"""{"code":"reminder_interest_limit","error":"synthetic-private-detail"}""")) else ReminderCreated(true,item=reminder(it),requestedContentId=it.contentId)}
        f.gateway.revoke={limited=false}
        f.controller.load(false);runCurrent();f.controller.create(choice) {};runCurrent()
        assertEquals(ReminderRecovery.MANAGE_REMINDERS,f.controller.state.value.recovery)
        assertEquals("Stop active reminders or dismiss old inbox updates before adding another.",f.controller.state.value.error)
        var opened=false;f.controller.manageReminders {opened=true};assertTrue(opened)
        val manager=ReminderController(backgroundScope,f.gateway,f.contexts,{true});manager.load();runCurrent();manager.revoke(reminder());runCurrent()
        val next=ReminderController(backgroundScope,f.gateway,f.contexts,{true});next.load(false);runCurrent();next.create(choice) {f.notices++};runCurrent();assertEquals(1,f.notices)
    }
    @Test fun `late recovery completion cannot notify a different profile`()=runTest {
        val f=Fixture(this);f.gateway.create={throw ApiError.Http(HttpFailure(409,"""{"code":"reminder_metadata_reload_required"}"""))}
        f.controller.load(false);runCurrent();f.controller.create(choice) {};runCurrent()
        val done=CompletableDeferred<Unit>();f.controller.reloadTitle({withContext(NonCancellable) {done.await()}}) {f.notices++};runCurrent()
        f.contexts.value++;runCurrent();done.complete(Unit);runCurrent();assertEquals(0,f.notices);assertTrue(f.controller.state.value.closed)
    }
    @Test fun `safe open title uses loaded server identity and refuses replacement contexts`()=runTest {
        val f=Fixture(this);f.controller.load();runCurrent()
        assertEquals(movie.titleId,f.controller.openReminder(reminder())!!.titleId)
        assertEquals(movie.titleId,f.controller.openEvent(event())!!.titleId)
        assertNull(f.controller.openEvent(event("unloaded")))
        assertNull(reminderDetailsTarget(movie.titleId,"movie",Title("different","Different",mediaType="movie")))
        assertNull(reminderDetailsTarget("https://synthetic.invalid/title","movie",Title("https://synthetic.invalid/title","Unsafe",mediaType="movie")))
        f.contexts.value++;assertNull(f.controller.openEvent(event()));assertTrue(f.controller.state.value.closed)
    }
    @Test fun `event labels and device descriptions never imply playable media or expose internal monitoring enums`() {
        for(kind in listOf("release","new_episode","matching_source")) {
            val text=reminderDescription(kind,"digital")
            assertFalse(text.contains("now playable",true));assertFalse(text.contains("observations_only"))
        }
        assertEquals("Updates when this title is checked in the app.",ReminderPassiveExplanation)
        assertEquals("Source checks for another device",reminderDeviceLabel(reminder().copy(createdOnCurrentDevice=false)))
        assertNull(reminderDeviceLabel(reminder().copy(eventKind="new_episode")))
    }
    @Test fun `historical event timestamps parse safely in the chosen locale and device zone`() {
        val zone=ZoneId.of("Europe/Berlin")
        assertTrue(reminderRecordedLabel("2026-07-15T12:00:00Z",Locale.GERMANY,zone)!!.contains("14:00"))
        assertTrue(reminderRecordedLabel("2026-11-15T12:00:00Z",Locale.GERMANY,zone)!!.contains("13:00"))
        for(raw in listOf(null,"","not-a-date","synthetic-private-password","x".repeat(1000))) assertNull(reminderRecordedLabel(raw,Locale.GERMANY,zone))
    }
    @Test fun `request builders reject invented whole series sources and rewritten alternate ordering`() {
        assertThrows(IllegalArgumentException::class.java) {reminderBody(series.titleId,"series","matching_source")}
        assertThrows(IllegalArgumentException::class.java) {reminderBody(series.titleId,"series","release","digital")}
        assertThrows(IllegalArgumentException::class.java) {reminderBody(series.titleId,"series","matching_source",episode=ReminderEpisode("ep",EpisodeNumbering(series.titleId,"dvd"),1,1))}
        assertThrows(IllegalArgumentException::class.java) {reminderEventBody()}
    }
    @Test fun `real namespace wire fixtures contain only the approved request contract`() {
        val fixtures=mutableMapOf<String,JsonObject>()
        reminderChoices(ReminderTarget(movie)).forEach {fixtures["movie-${it.eventKind}-${it.releaseType?:"candidate"}"]=it.body()}
        for((provider,id) in listOf("tmdb" to "tmdb:series:41","tvdb" to "tvdb:series:74852")) {
            val title=series.copy(titleId=id);val ep=episode.copy(numbering=EpisodeNumbering(id,"official"))
            reminderChoices(ReminderTarget(title,ep)).forEach {fixtures["$provider-${it.eventKind}"]=it.body()}
        }
        fixtures["cross-provider-matching_source"]=reminderChoices(ReminderTarget(series,episode.copy(numbering=EpisodeNumbering("tvdb:series:74852","official")))) .last().body()
        fixtures["event-read"]=reminderEventBody(read=true);fixtures["event-unread"]=reminderEventBody(read=false)
        fixtures["event-dismiss"]=reminderEventBody(dismissed=true);fixtures["event-restore"]=reminderEventBody(dismissed=false)
        for((name,body) in fixtures) {
            assertEquals(body,ApiJson.decodeFromString<JsonObject>(ApiJson.encodeToString(JsonObject.serializer(),body)))
            assertTrue(body.keys.none {it in setOf("name","title","artwork","url","clientId","date")})
            System.getenv("MEDIASTORM_NATIVE_REMINDER_CONTRACT_DIR")?.let {directory->File(directory).mkdirs();File(directory,"$name.json").writeText(body.toString())}
        }
    }
}
