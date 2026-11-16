package com.strmr.tv.home

import com.strmr.tv.api.ApiJson
import com.strmr.tv.ui.lists.ListGridModel
import java.security.MessageDigest
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Unmodified actual Go handler/OpenAPI responses; no optional fixture path or environment skip. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GoCatalogueLanguageContractTest {
    private fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/catalogue-language-go/$name")).use{it.readBytes()}
    private fun page(name: String, list: BrowseList = BrowseList.Watchlist) = decodeBrowsePage(String(bytes(name)),list)
    private fun ids(page: BrowsePage) = page.entries.map { when(it) { is BrowseEntry.ItemEntry -> it.item.id;is BrowseEntry.TitleEntry -> it.title.id;else -> error("Unexpected wire shape") } }
    @Test fun `all ten actual Go handler responses retain exact producer hashes`() {
        val hashes=ApiJson.parseToJsonElement(String(bytes("sha256.json"))).jsonObject
        assertEquals(10,hashes.size)
        hashes.forEach { (name,value) ->
            val actual=MessageDigest.getInstance("SHA-256").digest(bytes(name)).joinToString(""){"%02x".format(it)}
            assertEquals(name,value.jsonPrimitive.content,actual)
        }
        val manifest=ApiJson.parseToJsonElement(String(bytes("manifest.json"))).jsonObject
        assertEquals("TestCatalogueLanguageActualHandlerWireAndOpenAPI",manifest.getValue("producer").jsonPrimitive.content)
    }
    @Test fun `actual selected and continuation rows preserve filtered universe total and capability`() {
        val first=page("watchlist-selected.json");val next=page("watchlist-continuation.json")
        assertEquals(listOf("tmdb:movie:2"),ids(first));assertEquals(listOf("tmdb:movie:3"),ids(next))
        assertEquals(2,first.total);assertEquals(2,next.total);assertTrue(first.originalLanguageFilterSupported);assertTrue(next.originalLanguageFilterSupported)
        assertEquals(6,page("watchlist-unfiltered.json").total)
    }
    @Test fun `unknown only OR and rare language responses have distinct real server results`() {
        assertEquals(listOf("tmdb:movie:4"),ids(page("watchlist-unknown.json")))
        assertEquals(listOf("tmdb:movie:2","tmdb:movie:4"),ids(page("watchlist-code-or-unknown.json")))
        assertEquals(listOf("tmdb:movie:5"),ids(page("watchlist-rare.json")))
        assertEquals(listOf("tmdb:movie:2","tmdb:movie:3"),ids(page("watchlist-random-pool.json")))
    }
    @Test fun `actual custom and ranked permanent shapes decode nonempty stable IDs`() {
        val list=BrowseList.Custom("synthetic-list","Synthetic")
        assertEquals(listOf("tmdb:movie:2","tmdb:movie:3"),ids(page("custom-list-selected.json",list)))
        val permanent=page("permanent-prequeue-selected.json",BrowseList.PermanentPrequeue)
        assertEquals(listOf("tmdb:movie:2","tmdb:movie:3"),ids(permanent));assertTrue(permanent.originalLanguageFilterSupported);assertEquals(2,permanent.total)
        assertTrue(permanent.entries.all{it is BrowseEntry.TitleEntry})
    }
    @Test fun `older permanent flat replies remain compatible while actual ranked shape is supported`() {
        val old=decodeBrowsePage("{\"items\":[{\"id\":\"tmdb:movie:7\",\"name\":\"Synthetic old shape\"}],\"total\":1}",BrowseList.PermanentPrequeue)
        assertEquals(listOf("tmdb:movie:7"),ids(old));assertFalse(old.originalLanguageFilterSupported)
    }
    @Test fun `actual supported empty page advertises capability without invented rows`() {
        val empty=page("watchlist-empty.json");assertTrue(empty.entries.isEmpty());assertEquals(0,empty.total);assertTrue(empty.originalLanguageFilterSupported)
    }
    @Test fun `actual Go pages drive actual shared model pagination with same original-language query`() = runTest {
        val offsets=mutableListOf<Int>();val requests=mutableListOf<BrowseOptions>()
        val model=ListGridModel(BrowseList.Watchlist,emptyMap(),backgroundScope,readPage={ _,_,offset,_,options,_ ->
            offsets+=offset;requests+=options
            if(!options.originalLanguageActive)page("watchlist-unfiltered.json") else if(offset==0)page("watchlist-selected.json")else page("watchlist-continuation.json")
        })
        model.reload();assertTrue(model.originalLanguageFilterSupported)
        model.options=BrowseOptions(originalLanguages=setOf("fra"));model.reload();model.loadPage()
        assertEquals(listOf("tmdb:movie:2","tmdb:movie:3"),ids(BrowsePage(model.entries,model.total!!)))
        assertEquals(listOf(0,0,1),offsets);assertTrue(requests.drop(1).all{it.query(BrowseList.Watchlist)["originalLanguages"]=="fra"});assertEquals(2,model.total)
    }
    @Test fun `actual filtered model random selection reads filtered offset instead of unfiltered universe`() = runTest {
        var calls=0;var randomOffset:Int?=null;var opened:HomeLink?=null
        val model=ListGridModel(BrowseList.Watchlist,emptyMap(),backgroundScope,readPage={ _,limit,offset,_,options,_ ->
            calls++;assertEquals(setOf("fra"),options.originalLanguages)
            if(limit==1){randomOffset=offset;page("watchlist-continuation.json")}else page("watchlist-selected.json")
        },randomOffset={count->assertEquals(2,count);1})
        model.options=BrowseOptions(originalLanguages=setOf("fra"));model.reload();model.pickRandom{opened=it};runCurrent()
        assertEquals(2,calls);assertEquals(1,randomOffset);assertEquals("tmdb:movie:3",(opened as HomeLink.Details).target.titleId)
    }
    @Test fun `malformed permanent wire row fails entire page instead of losing its offset`() {
        for (bad in listOf("{\"rank\":1,\"title\":{\"name\":\"Missing ID\"}}", "null", "{\"name\":\"Missing ID\"}")) {
            val raw="{\"items\":[$bad,{\"id\":\"tmdb:movie:7\",\"name\":\"Valid later row\"}],\"total\":2,\"originalLanguageFilterSupported\":true}"
            assertNotNull(runCatching { decodeBrowsePage(raw,BrowseList.PermanentPrequeue) }.exceptionOrNull())
        }
    }
    @Test fun `actual model does not advance offset after a malformed permanent page`() = runTest {
        val raw="{\"items\":[{\"rank\":1,\"title\":{\"name\":\"Missing ID\"}},{\"id\":\"tmdb:movie:7\",\"name\":\"Valid later row\"}],\"total\":2,\"originalLanguageFilterSupported\":true}"
        val offsets=mutableListOf<Int>()
        val model=ListGridModel(BrowseList.PermanentPrequeue,emptyMap(),backgroundScope,readPage={ _,_,offset,_,_,_ -> offsets+=offset;decodeBrowsePage(raw,BrowseList.PermanentPrequeue) })
        model.reload();assertTrue(model.entries.isEmpty());assertEquals(0,model.fetched);assertNull(model.total);assertNotNull(model.loadError)
        assertEquals(listOf(0),offsets)
    }

}
