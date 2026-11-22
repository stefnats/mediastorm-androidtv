package com.strmr.tv.home

import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.ui.lists.ListGridModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CachedStoredMediaTest {
    private val options=BrowseOptions(originalLanguages=setOf("fra"), storedMedia=StoredMediaFilters(audioLanguage="de",audioCodec="ac3",unknown="only"))
    private fun capable(raw:String):String=JsonObject(ApiJson.parseToJsonElement(raw).jsonObject + mapOf(
        "storedMediaFilterSupported" to JsonPrimitive(true), "storedMediaFilterBasis" to JsonPrimitive(StoredMediaFilters.BASIS))).toString()
    private fun resource(path:String)=requireNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use { it.readText() }
    private fun collection(parent:Job)=requireNotNull(BrowseList.CollectionCached.open(CollectionRef(77,"Collection"),ProfileDataContext.current,parent){true})
    private fun changed(raw:String, descriptor:String, key:String, value:JsonElement):String {
        val root=ApiJson.parseToJsonElement(capable(raw)).jsonObject
        return JsonObject(root + (descriptor to JsonObject(root.getValue(descriptor).jsonObject + (key to value)))).toString()
    }
    @Test fun `actual specialized request builders carry media filters with original selectors unchanged`()=runTest {
        val parent=coroutineContext[Job]!!
        val requests=listOf(
            cachedCollectionRequest("profile",collection(parent),2,2,options).query,
            cachedSimilarRequest("profile",SimilarFixture.list(producer=parent),2,2,options).query,
            cachedTMDBPublicListRequest("profile",PublicListFixture.list(producer=parent),2,2,options).query,
            recentActivityRequest("profile",RecentActivityFixture.list(producer=parent),2,2,options).query,
            popularActivityRequest("profile",PopularActivityFixture.list(producer=parent),2,2,options).query,
            cachedWatchingRequest("profile",WatchingFixture.list(producer=parent),2,2,options).query,
            hiddenManagementQuery(2,2,emptyMap(),options), storedLovedRequest("profile","synthetic-client",2,2,options).query,
            browseQuery(BrowseList.PermanentPrequeue,2,2,options=options))
        assertEquals(setOf("collection-cached","similar-cached","tmdb-public-list-cached","recently-watched-cached","popular-on-server-cached","continue-watching-cached","hidden","loved-stored","permanent-prequeue"),requests.map { it.getValue("source") }.toSet())
        requests.forEach { q ->
            assertEquals("deu",q["audioLanguage"]); assertEquals("ac3",q["audioCodec"]); assertEquals("only",q["mediaUnknown"])
            assertEquals("fra",q["originalLanguages"]); assertEquals("2",q["offset"]); assertEquals("2",q["limit"])
        }
        val person=BrowseList.Remote("person-cache","Saved credits",mapOf("source" to "tmdb-person-credits-cached","personId" to "77"))
        assertEquals("deu",browseQuery(person,2,0,options=options)["audioLanguage"])
        assertFalse(BrowseList.Remote("person-live","Credits",mapOf("source" to "tmdb-person-credits")).storedMediaEligible())
    }
    @Test fun `cached decoders preserve exact rows totals and snapshot observations with additive media support`() {
        val collection=decodeCachedCollectionPage(capable(CollectionFixture.wire("unfiltered")),77,2,0,CollectionFixture.now)
        val watching=decodeCachedWatchingPage(capable(WatchingFixture.wire("unfiltered")),WatchingFixture.clock(),2,0)
        val similar=decodeCachedSimilarPage(capable(SimilarFixture.synthetic(2,0,2)),SimilarFixture.list(),SimilarFixture.clock(),2,0)
        val publicList=decodeCachedTMDBPublicListPage(capable(PublicListFixture.synthetic(2,0,2)),PublicListFixture.list(),PublicListFixture.clock(),2,0)
        val pages=listOf(collection,watching,similar,publicList,
            decodeRecentActivityPage(capable(RecentActivityFixture.synthetic(2,0,2)),2,0),
            decodePopularActivityPage(capable(PopularActivityFixture.synthetic(2,0,2)),2,0),
            decodeHiddenManagementPage(capable(resource("/management-language-go/hidden/hidden-selected.json"))),
            decodeStoredLovedPage(capable(resource("/loved-stored-go/loved-stored-selected.json"))))
        pages.forEach { assertTrue(it.storedMediaFilterSupported); assertEquals(StoredMediaFilters.BASIS,it.storedMediaFilterBasis); assertTrue(it.originalLanguageFilterSupported) }
        assertEquals(CollectionFixture.page("unfiltered",2).collection,collection.collection)
        assertTrue(WatchingFixture.page("unfiltered",2).watching!!.samePool(watching.watching!!))
        assertEquals("returned-results",similar.similar?.scope); assertEquals("returned-items",publicList.publicList?.scope)
        assertEquals(701,collection.total); assertEquals(700,watching.total)
    }
    @Test fun `media capability never relaxes expired mismatched or malformed cached snapshots`() {
        val invalid=listOf<()->BrowsePage>(
            { decodeCachedCollectionPage(changed(CollectionFixture.wire("unfiltered"),"collection","id",JsonPrimitive(78)),77,2,0,CollectionFixture.now) },
            { decodeCachedWatchingPage(changed(WatchingFixture.wire("unfiltered"),"pool","expiresAt",JsonPrimitive("2000-01-01T00:00:00Z")),WatchingFixture.clock(),2,0) },
            { decodeCachedSimilarPage(capable(SimilarFixture.synthetic(2,0,2,seed=78)),SimilarFixture.list(),SimilarFixture.clock(),2,0) },
            { decodeCachedTMDBPublicListPage(capable(PublicListFixture.synthetic(2,0,2,expires=PublicListFixture.date-1)),PublicListFixture.list(),PublicListFixture.clock(),2,0) },
            { decodeRecentActivityPage(capable(RecentActivityFixture.synthetic(3,0,1)),2,0) })
        invalid.forEach { assertTrue(runCatching(it).exceptionOrNull() is ManagementPageFailure) }
    }
    @Test fun `hidden active media filter cannot negotiate an unfiltered legacy fallback`()=runTest {
        var fallback=0
        val result=runCatching { readHiddenManagementPage(2,0,emptyMap(),options.copy(originalLanguages=emptySet()),ProfileDataContext.current,null,
            readPage={ query,_->
                assertEquals("deu",query["audioLanguage"]); assertEquals("only",query["mediaUnknown"])
                assertFalse(query.containsKey("originalLanguages"))
                throw ApiError.Http(HttpFailure(400,"unsupported display list source\n"))
            },
            readLegacy={ fallback++; "[]" }) }
        assertTrue(result.isFailure); assertEquals(0,fallback)
    }
    @Test fun `actual collection model exposes media controls and retains same captured snapshot when filtering`()=runTest {
        val parent=coroutineContext[Job]!!; val selected=collection(parent); val calls=mutableListOf<BrowseOptions>()
        val model=ListGridModel(selected,emptyMap(),backgroundScope,readPage={ _,_,_,_,o,_ ->
            calls+=o
            decodeCachedCollectionPage(capable(CollectionFixture.wire("unfiltered")),77,2,0,CollectionFixture.now)
        },clockMillis={CollectionFixture.now})
        model.reload(); assertTrue(model.storedMediaFilterSupported)
        val observed=model.collectionObservation
        model.options=options; model.reload()
        assertTrue(model.storedMediaFilterSupported); assertEquals(observed,model.collectionObservation)
        assertEquals("deu",calls.last().storedMedia.audioLanguage); assertEquals(setOf("fra"),calls.last().originalLanguages)
    }
}
