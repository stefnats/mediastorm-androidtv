package com.strmr.tv.api

import com.strmr.tv.home.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Actual display-list query builder, portable sender and page decoder over local HTTP. */
class StoredMediaTransportTest {
    @Test fun `saved shelf sends file facts before paging and reads stored version capability`()=runBlocking {
        val list=BrowseList.Remote("shelf","Shelf",mapOf("source" to "mdblist-shelf","url" to "mediastorm:custom-list:synthetic"))
        val options=BrowseOptions(storedMedia=StoredMediaFilters(audioLanguage="fr",audioCodec="ac3",minHeight=1080,unknown="only"))
        val query=browseQuery(list,1,81,options=options)
        val wire="""{"items":[],"total":2,"sourceTotal":100,"storedMediaFilterSupported":true,"storedMediaFilterBasis":"stored_library_versions"}"""
        WatchingPeer(listOf(WatchingReply(body=wire))).use { peer ->
            val client=OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            try {
                val url=peer.url.newBuilder().query(null).apply { query.forEach { (key,value)->addQueryParameter(key,value) } }.build()
                val context=RequestContext({0L},{}).capture(0)
                val result=ContextBoundTransport(context,false,{false},{error("No fallback")}).execute {
                    client.newCall(Request.Builder().url(url).build()).await()
                }
                val page=decodeBrowsePage(result.text,list)
                assertTrue(page.storedMediaFilterSupported); assertEquals(StoredMediaFilters.BASIS,page.storedMediaFilterBasis); assertEquals(2,page.total)
                val line=peer.lines.single(); assertTrue(line.startsWith("GET /api/users/synthetic/display-list?"))
                for (pair in listOf("source=mdblist-shelf","audioLanguage=fra","audioCodec=ac3","minHeight=1080","mediaUnknown=only","limit=1","offset=81")) assertTrue(line,line.contains(pair))
                assertEquals(1,peer.count.get())
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }
}
