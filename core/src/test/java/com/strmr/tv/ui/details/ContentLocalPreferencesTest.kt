package com.strmr.tv.ui.details

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.ApiError
import com.strmr.tv.api.HttpFailure
import com.strmr.tv.api.ContentLocalPreference
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.Episode
import com.strmr.tv.api.EpisodeNumbering
import com.strmr.tv.api.LibraryItem
import com.strmr.tv.api.LocalSourceChoice
import com.strmr.tv.api.Season
import com.strmr.tv.api.contentLocalPreferenceBody
import com.strmr.tv.library.LibraryVersion
import com.strmr.tv.library.LibraryVersions
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContentLocalPreferencesTest {
    private val title=DetailsTarget("tmdb:series:123","Synthetic series",false)
    private val episode=Episode("episode",seasonNumber=2,episodeNumber=3,numbering=EpisodeNumbering(title.titleId,"official"))
    private val targets=contentLocalScopes(title,episode=episode)
    private inner class Fixture(val test: TestScope) {
        val contexts=MutableStateFlow(7L)
        val reads=mutableListOf<Pair<ContentLocalScope,Long>>()
        val writes=mutableListOf<Triple<ContentLocalScope,Boolean?,Long>>()
        var notices=0
        var read: suspend (ContentLocalScope,Long)->ContentLocalPreference = { _,_->ContentLocalPreference(null,true) }
        var write: suspend (ContentLocalScope,Boolean?,Long)->ContentLocalPreference = { _,v,_->ContentLocalPreference(v,true) }
        val editor=ContentLocalPreferences(test.backgroundScope,targets,contexts,
            { target,context->reads+=target to context;read(target,context) },
            { target,value,context->writes+=Triple(target,value,context);write(target,value,context) }, {notices++})
    }

    @Test fun `canonical hierarchy includes title selected season and exact episode`() {
        assertEquals(listOf("tmdb:series:123","tmdb:series:123:season:2","tmdb:series:123:season:2:episode:3"),targets.map { it.id })
        val season=Season("season",number=2,episodes=listOf(episode))
        assertEquals(targets.take(2),contentLocalScopes(title,season))
        assertEquals("Season 2",targets[1].label)
    }
    @Test fun `canonical TMDB and TVDB identities and legacy aliases keep scoped coordinates`() {
        for (id in listOf("tmdb:series:123", "tvdb:series:74852", "tmdb:tv:123", "tvdb:74852", "tt12345")) {
            val target=title.copy(titleId=id)
            val ep=episode.copy(numbering=EpisodeNumbering(id,"official"))
            assertEquals(listOf(id,"$id:season:2","$id:season:2:episode:3"),contentLocalScopes(target,episode=ep).map { it.id })
            assertEquals(listOf(id,"$id:season:2"),contentLocalScopes(target,Season("season",number=2,episodes=listOf(ep))).map { it.id })
            assertTrue(contentLocalScopes(target.copy(isMovie=true),episode=ep).isEmpty() || id.startsWith("tt"))
        }
    }
    @Test fun `opaque catalog aliases retain title scope without trusting their episode coordinates`()=runTest {
        val target=title.copy(titleId="catalog:custom-series:123")
        val ep=episode.copy(numbering=EpisodeNumbering(target.titleId,"official"))
        assertEquals(listOf(target.titleId),contentLocalScopes(target,episode=ep).map { it.id })
        val calls=mutableListOf<String>()
        assertEquals(false,resolveContentLocalPreference(target,ep,7) {scope,_->calls+=scope.id;ContentLocalPreference(false,true)})
        assertEquals(listOf(target.titleId),calls)
    }
    @Test fun `invalid scope identities and out of range coordinates cannot form server patches`() {
        for (id in listOf("", "https://catalog.invalid/123", "catalog bad", "catalog:123:season:2", "catalog:123:episode:2", "a".repeat(1025))) {
            assertTrue(contentLocalScopes(title.copy(titleId=id),episode=episode).isEmpty())
        }
        for (ep in listOf(episode.copy(seasonNumber=10_001),episode.copy(episodeNumber=100_001))) {
            assertEquals(listOf(title.titleId),contentLocalScopes(title,episode=ep).map { it.id })
        }
    }
    @Test fun `real TMDB and TVDB scoped source saves contain only exact source patch fields`()=runTest {
        for (id in listOf("tmdb:series:123","tvdb:series:74852")) {
            val target=title.copy(titleId=id)
            val ep=episode.copy(numbering=EpisodeNumbering(id,"official"))
            val scopes=contentLocalScopes(target,episode=ep)
            for (selected in scopes) {
                val requests=mutableListOf<JsonObject>()
                val editor=ContentLocalPreferences(backgroundScope,scopes,MutableStateFlow(7L),
                    {_,_->ContentLocalPreference(null,true)},
                    {scope,value,context->
                        assertEquals(7L,context)
                        requests+=contentLocalPreferenceBody(scope.id,scope.contentType,value)
                        ContentLocalPreference(value,true)
                    },{})
                editor.load();runCurrent();editor.select(selected);editor.choose(LocalSourceChoice.NORMAL);runCurrent()
                val request=requests.single()
                assertEquals(setOf("contentId","contentType","preferLocalFiles"),request.keys)
                assertEquals(selected.id,request["contentId"]!!.jsonPrimitive.content)
                assertEquals("series",request["contentType"]!!.jsonPrimitive.content)
                assertFalse(request["preferLocalFiles"]!!.jsonPrimitive.boolean)
                assertTrue(editor.state.value.closed)
            }
        }
    }
    @Test fun `real series normal Play applies explicit false from episode season or title over available libraries`()=runTest {
        for (id in listOf("tmdb:series:123","tvdb:series:74852")) {
            val target=title.copy(titleId=id)
            val ep=episode.copy(numbering=EpisodeNumbering(id,"official"))
            val scopes=contentLocalScopes(target,episode=ep)
            for (source in listOf("local","plex","jellyfin")) {
                val copy=LibraryVersion(LibraryItem("available-$source",seasonNumber=2,episodeNumber=3,sourceType=source),"Synthetic library")
                val library=LibraryVersions(emptyList(),mapOf(LibraryVersions.Key(2,3) to listOf(copy)))
                assertEquals(copy,library.best(2,3))
                for (override in scopes.indices) {
                    val calls=mutableListOf<String>()
                    val selected=catalogPlayLibrary(target,ep,library,7) {scope,context->
                        assertEquals(7L,context);calls+=scope.id
                        ContentLocalPreference(if(scope==scopes[override])false else if(scopes.indexOf(scope)<override)true else null,true)
                    }
                    assertNull("$id $source ${scopes[override].id}",selected)
                    assertEquals(scopes.drop(override).asReversed().map {it.id},calls)
                }
                for (inherited in listOf<Boolean?>(true,null)) {
                    assertSame(library,catalogPlayLibrary(target,ep,library,7) {_,_->ContentLocalPreference(inherited,true)})
                }
                assertSame(library,catalogPlayLibrary(target.copy(fromLibrary=true),ep,library,7) {_,_->error("Deliberate library Play must not load a catalog override")})
            }
        }
    }
    @Test fun `alternate and cross provider normal Play reads only safe title override`()=runTest {
        val library=LibraryVersions(listOf(LibraryVersion(LibraryItem("available",sourceType="plex"),"Synthetic library")),emptyMap())
        for (ep in listOf(episode.copy(numbering=EpisodeNumbering(title.titleId,"dvd")),episode.copy(numbering=EpisodeNumbering("tvdb:series:74852","official")))) {
            val calls=mutableListOf<String>()
            assertNull(catalogPlayLibrary(title,ep,library,7) {scope,_->calls+=scope.id;ContentLocalPreference(false,true)})
            assertEquals(listOf(title.titleId),calls)
            assertSame(library,catalogPlayLibrary(title,ep,library,7) {_,_->ContentLocalPreference(null,true)})
        }
    }
    @Test fun `movies never acquire season scope and unmatched library IDs are hidden`() {
        val movie=DetailsTarget("tmdb:movie:44","Synthetic movie",true)
        assertEquals(listOf("tmdb:movie:44"),contentLocalScopes(movie,episode=episode).map { it.id })
        assertTrue(contentLocalScopes(title.copy(titleId="unmatched-library-file",fromLibrary=true)).isEmpty())
        assertTrue(contentLocalScopes(title.copy(titleId="tmdb:movie:44")).isEmpty())
    }
    @Test fun `alternate unknown or other-provider numbering only offers title scope`() {
        for (ep in listOf(episode.copy(numbering=null),episode.copy(numbering=EpisodeNumbering(title.titleId,"dvd")),episode.copy(numbering=EpisodeNumbering("tvdb:999","official")),episode.copy(episodeNumber=0),episode.copy(seasonNumber=-1))) {
            assertEquals(listOf(title.titleId),contentLocalScopes(title,episode=ep).map { it.id })
        }
    }
    @Test fun `mixed numbered seasons cannot receive a canonical season override`() {
        val season=Season("s",number=2,episodes=listOf(episode,episode.copy(id="different",numbering=EpisodeNumbering(title.titleId,"absolute"))))
        assertEquals(1,contentLocalScopes(title,season).size)
    }
    @Test fun `specials use season zero without converting it to regular season one`() {
        val special=episode.copy(seasonNumber=0,episodeNumber=1)
        assertEquals("tmdb:series:123:season:0:episode:1",contentLocalScopes(title,episode=special).last().id)
    }
    @Test fun `false is a real override and episode season title precedence is preserved`() {
        assertEquals(false,effectiveContentLocalPreference(targets,mapOf(targets[0].id to true,targets[1].id to true,targets[2].id to false)))
        assertEquals(true,effectiveContentLocalPreference(targets,mapOf(targets[0].id to false,targets[1].id to true,targets[2].id to null)))
        assertEquals(false,effectiveContentLocalPreference(targets,mapOf(targets[0].id to false)))
        assertNull(effectiveContentLocalPreference(targets,targets.associate {it.id to null}))
    }
    @Test fun `source patch keeps explicit null and has no language or subtitle mutation`() {
        for (preference in listOf(null,true,false)) {
            val body=contentLocalPreferenceBody(targets[1].id,"series",preference)
            val encoded=ApiJson.encodeToString(JsonObject.serializer(),body)
            val decoded=ApiJson.decodeFromString<JsonObject>(encoded)
            assertEquals(setOf("contentId","contentType","preferLocalFiles"),decoded.keys)
            if(preference==null) assertEquals(JsonNull,decoded["preferLocalFiles"]) else assertEquals(preference,decoded["preferLocalFiles"]!!.jsonPrimitive.boolean)
        }
    }
    @Test fun `omitted inherited bool requires an explicit capability marker`() {
        assertEquals(ContentLocalPreference(null,true),ApiJson.decodeFromString<ContentLocalPreference>("""{"localSourcePreferenceSupported":true}"""))
        assertFalse(ApiJson.decodeFromString<ContentLocalPreference>("{}").localSourcePreferenceSupported)
        assertEquals(false,ApiJson.decodeFromString<ContentLocalPreference>("""{"localSourcePreferenceSupported":true,"preferLocalFiles":false}""").preferLocalFiles)
    }
    @Test fun `capability absence prevents destructive legacy document writes`()=runTest {
        val f=Fixture(this);f.read={_,_->ContentLocalPreference()};f.editor.load();runCurrent()
        assertFalse(f.editor.state.value.supported);assertEquals(1,f.reads.size)
        f.editor.choose(LocalSourceChoice.LOCAL);runCurrent();assertTrue(f.writes.isEmpty());assertEquals(0,f.notices)
    }
    @Test fun `selection cannot save until all requested scope reads finish`()=runTest {
        val f=Fixture(this);val reply=CompletableDeferred<ContentLocalPreference>();f.read={_,_->reply.await()}
        f.editor.load();runCurrent();f.editor.choose(LocalSourceChoice.NORMAL);runCurrent();assertTrue(f.writes.isEmpty())
        reply.complete(ContentLocalPreference(null,true));runCurrent();assertTrue(f.editor.state.value.supported)
    }
    @Test fun `season save uses its immutable ID and original identity once`()=runTest {
        val f=Fixture(this);f.editor.load();runCurrent();f.editor.select(targets[1]);f.editor.choose(LocalSourceChoice.LOCAL);f.editor.choose(LocalSourceChoice.NORMAL);runCurrent()
        assertEquals(listOf(Triple(targets[1],true,7L)),f.writes);assertEquals(1,f.notices);assertTrue(f.editor.state.value.closed)
    }
    @Test fun `inherit submits null and clears the stored scope`()=runTest {
        val f=Fixture(this);f.read={_,_->ContentLocalPreference(false,true)};f.editor.load();runCurrent();f.editor.choose(LocalSourceChoice.INHERIT);runCurrent()
        assertNull(f.writes.single().second);assertNull(f.editor.state.value.values[targets[0].id]);assertEquals(1,f.notices)
    }
    @Test fun `wrong server echo is not success and requires a reload`()=runTest {
        val f=Fixture(this);f.write={_,_,_->ContentLocalPreference(false,true)};f.editor.load();runCurrent();f.editor.choose(LocalSourceChoice.LOCAL);runCurrent()
        assertFalse(f.editor.state.value.closed);assertFalse(f.editor.state.value.supported);assertEquals(0,f.notices)
        f.editor.choose(LocalSourceChoice.NORMAL);runCurrent();assertEquals(1,f.writes.size)
    }
    @Test fun `transport errors do not expose credentials and do not retry writes`()=runTest {
        val f=Fixture(this);f.write={_,_,_->throw IOException("synthetic-private-password")};f.editor.load();runCurrent();f.editor.choose(LocalSourceChoice.LOCAL);runCurrent()
        assertEquals(1,f.writes.size);assertFalse(f.editor.state.value.error.orEmpty().contains("synthetic-private"));assertEquals(0,f.notices)
        f.editor.choose(LocalSourceChoice.LOCAL);runCurrent();assertEquals(1,f.writes.size)
    }
    @Test fun `profile account and server changes invalidate pending reads`()=runTest {
        for(label in listOf("profile","account","server")) {
            val f=Fixture(this);val reply=CompletableDeferred<ContentLocalPreference>();f.read={_,_->withContext(NonCancellable){reply.await()}}
            f.editor.load();runCurrent();f.contexts.value++;runCurrent();reply.complete(ContentLocalPreference(true,true));runCurrent()
            assertTrue(label,f.editor.state.value.closed);assertTrue(label,f.writes.isEmpty());assertEquals(0,f.notices)
        }
    }
    @Test fun `late write success cannot alter or notify a replacement profile`()=runTest {
        val f=Fixture(this);val reply=CompletableDeferred<ContentLocalPreference>();f.write={_,_,_->withContext(NonCancellable){reply.await()}}
        f.editor.load();runCurrent();f.editor.choose(LocalSourceChoice.NORMAL);runCurrent();f.contexts.value++;runCurrent();reply.complete(ContentLocalPreference(false,true));runCurrent()
        assertTrue(f.editor.state.value.closed);assertEquals(0,f.notices);assertEquals(7L,f.writes.single().third)
    }
    @Test fun `disposal drops late requests and an old selection cannot reopen`()=runTest {
        val f=Fixture(this);f.editor.load();runCurrent();f.editor.close();f.editor.choose(LocalSourceChoice.LOCAL);f.editor.load();runCurrent()
        assertTrue(f.writes.isEmpty());assertTrue(f.editor.state.value.closed)
    }
    @Test fun `an old page cannot retarget its editor after a profile switch`()=runTest {
        val contexts=MutableStateFlow(8L);var reads=0;var writes=0
        val editor=ContentLocalPreferences(backgroundScope,targets,contexts,{_,_->reads++;ContentLocalPreference(null,true)},{_,v,_->writes++;ContentLocalPreference(v,true)},{},expected=7)
        editor.load();editor.choose(LocalSourceChoice.LOCAL);runCurrent()
        assertEquals(0,reads);assertEquals(0,writes);assertTrue(editor.state.value.closed)
    }
    @Test fun `a scope cannot switch while its write is in flight`()=runTest {
        val f=Fixture(this);val reply=CompletableDeferred<ContentLocalPreference>();f.write={_,_,_->reply.await()}
        f.editor.load();runCurrent();f.editor.select(targets[1]);f.editor.choose(LocalSourceChoice.LOCAL);runCurrent();f.editor.select(targets[2])
        assertEquals(targets[1],f.editor.state.value.selected);reply.complete(ContentLocalPreference(true,true));runCurrent();assertEquals(targets[1],f.writes.single().first)
    }
    @Test fun `normal catalog selection bypasses library-first but explicit library choices remain`() {
        val library=LibraryVersions(emptyList(),emptyMap())
        assertNull(automaticLibraryForPreference(library,false,false))
        assertSame(library,automaticLibraryForPreference(library,false,true))
        assertSame(library,automaticLibraryForPreference(library,false,null))
        assertSame(library,automaticLibraryForPreference(library,true,false))
    }
    @Test fun `effective server lookup stops at the most specific explicit false`()=runTest {
        val calls=mutableListOf<Pair<String,Long>>()
        val result=resolveContentLocalPreference(title,episode,7) {scope,context->calls+=scope.id to context;ContentLocalPreference(false,true)}
        assertEquals(false,result);assertEquals(listOf(targets[2].id to 7L),calls)
    }
    @Test fun `effective lookup inherits through episode and season before title`()=runTest {
        val calls=mutableListOf<String>()
        val result=resolveContentLocalPreference(title,episode,7) {scope,_->calls+=scope.id;ContentLocalPreference(if(scope==targets[0])true else null,true)}
        assertEquals(true,result);assertEquals(targets.asReversed().map{it.id},calls)
    }
    @Test fun `alternate numbering resolves only the title-wide preference`()=runTest {
        val calls=mutableListOf<String>()
        val result=resolveContentLocalPreference(title,episode.copy(numbering=EpisodeNumbering(title.titleId,"dvd")),7) {scope,_->calls+=scope.id;ContentLocalPreference(false,true)}
        assertEquals(false,result);assertEquals(listOf(title.titleId),calls)
    }
    @Test fun `legacy missing read routes retain inherited playback without allowing edits`()=runTest {
        val result=resolveContentLocalPreference(title,episode,7) {_,_->throw ApiError.Http(HttpFailure(404,"synthetic legacy route"))}
        assertNull(result)
    }
    @Test fun `source preference fixtures are available for backend contract validation`() {
        System.getenv("MEDIASTORM_NATIVE_CONTRACT_DIR")?.let {dir->
            File(dir).mkdirs()
            for(choice in LocalSourceChoice.entries) {
                File(dir,"content-local-${choice.name.lowercase()}.json").writeText(contentLocalPreferenceBody(targets[1].id,"series",choice.preference).toString())
                File(dir,"content-local-tvdb-${choice.name.lowercase()}.json").writeText(contentLocalPreferenceBody("tvdb:series:74852:season:2","series",choice.preference).toString())
            }
        }
        assertEquals(3,LocalSourceChoice.entries.size)
    }
}
