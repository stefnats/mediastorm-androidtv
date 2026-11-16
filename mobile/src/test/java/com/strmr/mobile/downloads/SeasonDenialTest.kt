package com.strmr.mobile.downloads

import com.strmr.tv.api.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SeasonDenialTest {
    private val source=SourceResult.from(buildJsonObject {put("title","Synthetic pack")},0)!!
    private val episodes=(1..3).map {Episode("leaf-$it",seasonNumber=1,episodeNumber=it)}
    private fun denied()=ApiError.Http(HttpFailure(403,"""{"code":"kids_vod_access_denied"}"""))
    @Test fun `batch denial never falls back to individual episode resolves or enqueues files`()=runTest {
        var singles=0;var adds=0;val error=denied();var caught:Throwable?=null
        try {resolveSeasonDownloads(source,episodes,batch={throw error},single={singles++;"wrong"},enqueue={_,_,_->adds++})}catch(value:Throwable){caught=value}
        assertSame(error,caught);assertEquals(0,singles);assertEquals(0,adds)
    }
    @Test fun `individual denial stops the remaining season instead of treating it as an unavailable episode`()=runTest {
        var singles=0;val queued=mutableListOf<Int>()
        try {resolveSeasonDownloads(source,episodes,batch={emptyList()},single={singles++;if(singles==2)throw denied();"first.mkv"},enqueue={episode,_,_->queued+=episode.episodeNumber});fail()}catch(_:ApiError.Http){}
        assertEquals(2,singles);assertEquals(listOf(1),queued)
    }
    @Test fun `unavailable pack retains normal per episode fallback and actual numbering`()=runTest {
        val targeted=mutableListOf<String>();val queued=mutableListOf<Int>()
        resolveSeasonDownloads(source,episodes,batch={throw java.io.IOException("missing")},single={targeted+=it.attributes["targetEpisode"]!!;"file.mkv"},enqueue={episode,_,_->queued+=episode.episodeNumber})
        assertEquals(listOf("1","2","3"),targeted);assertEquals(listOf(1,2,3),queued)
    }
    @Test fun `cancelled batch never starts individual fallback`()=runTest {
        var singles=0
        try {resolveSeasonDownloads(source,episodes,batch={throw CancellationException("closed")},single={singles++;"wrong"},enqueue={_,_,_->fail()});fail()}catch(_:CancellationException){}
        assertEquals(0,singles)
    }
}
