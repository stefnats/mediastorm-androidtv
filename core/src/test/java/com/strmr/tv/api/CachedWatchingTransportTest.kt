package com.strmr.tv.api

import com.strmr.tv.home.*
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

internal data class WatchingReply(val status:Int=200,val date:String?="Mon, 16 Nov 2026 23:43:46 GMT",val body:String="{}",val duplicateDate:Boolean=false,val location:String?=null,val delayHeader:Long=0,val delayBody:Long=0,val entered:CountDownLatch?=null,val release:CountDownLatch?=null,val drop:Boolean=false,val rawBody:ByteArray?=null)
internal class WatchingPeer(private val replies:List<WatchingReply>):Closeable {
    private val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));private val executor=Executors.newSingleThreadExecutor()
    val count=AtomicInteger();val lines=java.util.concurrent.CopyOnWriteArrayList<String>()
    val url get()="http://127.0.0.1:${server.localPort}/api/users/synthetic/display-list?source=continue-watching-cached".toHttpUrl()
    init{executor.submit{try{for(reply in replies){server.accept().use{peer->peer.soTimeout=4000
        val reader=peer.getInputStream().bufferedReader();lines+=reader.readLine();while(true){val line=reader.readLine()?:break;if(line.isEmpty())break};count.incrementAndGet()
        if(!reply.drop){if(reply.delayHeader>0)Thread.sleep(reply.delayHeader)
            val bytes=reply.rawBody ?: reply.body.toByteArray();val headers=buildString{append("HTTP/1.1 ${reply.status} Synthetic\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n");reply.date?.let{append("Date: $it\r\n");if(reply.duplicateDate)append("Date: $it\r\n")};reply.location?.let{append("Location: $it\r\n")};append("\r\n")}
            val out=peer.getOutputStream();out.write(headers.toByteArray());out.flush();reply.entered?.countDown();reply.release?.await(4,TimeUnit.SECONDS)
            if(reply.delayBody>0)Thread.sleep(reply.delayBody);out.write(bytes);out.flush()
        }
    }}}catch(_:IOException){}catch(_:InterruptedException){}}}
    override fun close(){server.close();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))}
}

/** Real Call.await/transport/auth composition, distinct from successful Android ApiClient.send. */
class CachedWatchingTransportTest {
    private fun client()=OkHttpClient.Builder().retryOnConnectionFailure(false).build()
    private fun OkHttpClient.stop(){dispatcher.executorService.shutdownNow();connectionPool.evictAll()}
    private suspend fun get(client:OkHttpClient,peer:WatchingPeer)=client.newCall(Request.Builder().url(peer.url).build()).await(true)
    @Test fun `actual final success carries singleton Date RTT bounded body and original request URL`()=runBlocking {
        WatchingPeer(listOf(WatchingReply(body=WatchingFixture.wire("show-episode"),delayHeader=120,delayBody=80))).use{peer->val client=client();try{
            val response=get(client,peer);assertEquals(1,peer.count.get());val clock=response.observedClock(peer.url)
            val date=java.time.Instant.parse("2026-11-16T23:43:46Z").toEpochMilli();assertTrue(clock.upperMillis()>=date+1200)
            assertEquals(1,decodeCachedWatchingPage(response.text,clock,1).total)
            assertEquals("GET /api/users/synthetic/display-list?source=continue-watching-cached HTTP/1.1",peer.lines.single())
        }finally{client.stop()}}
    }
    @Test fun `actual missing duplicate malformed Date and exact URL mismatch never become clocks`()=runBlocking {
        for(reply in listOf(WatchingReply(date=null),WatchingReply(duplicateDate=true),WatchingReply(date="invalid"),WatchingReply(date="Mon, 30 Feb 2026 23:43:46 GMT"))){WatchingPeer(listOf(reply)).use{peer->val client=client();try{assertTrue(runCatching{get(client,peer).observedClock(peer.url)}.isFailure)}finally{client.stop()}}}
        WatchingPeer(listOf(WatchingReply())).use{peer->val client=client();try{val result=get(client,peer);assertTrue(runCatching{result.observedClock(peer.url.newBuilder().setQueryParameter("source","continue-watching").build())}.isFailure)}finally{client.stop()}}
    }
    @Test fun `actual redirect final URL cannot supply observation for original cache request`()=runBlocking {
        WatchingPeer(listOf(WatchingReply())).use{target->WatchingPeer(listOf(WatchingReply(status=302,location=target.url.toString()))).use{origin->val client=client();try{
            val result=get(client,origin);assertEquals(1,target.count.get());assertEquals(1,origin.count.get());assertTrue(runCatching{result.observedClock(origin.url)}.isFailure)
        }finally{client.stop()}}}
    }
    @Test fun `actual body bound and invalid UTF8 fail instead of creating partial observation`()=runBlocking {
        for (reply in listOf(WatchingReply(body="x".repeat(4*1024*1024+1)), WatchingReply(rawBody=byteArrayOf(0xc3.toByte(),0x28)))) {
            WatchingPeer(listOf(reply)).use{peer->val client=client();try{assertTrue(runCatching{get(client,peer)}.isFailure)}finally{client.stop()}}
        }
    }
    @Test fun `actual 401 authorization rotation uses only final accepted response clock`()=runBlocking {
        WatchingPeer(listOf(WatchingReply(status=401,date="Tue, 17 Nov 2026 23:43:46 GMT",body="{\"code\":\"unauthorized\"}"),WatchingReply())).use{peer->val client=client();try{
            val context=RequestContext({0L},{}).capture(0);var token="synthetic-old";var rotations=0;var final:HttpResult?=null
            val body=RequestAuthorization(context,{token},{false},{rotations++;token="synthetic-rotated";ApiClient.RefreshOutcome.REFRESHED},{error("No signout")}){
                val result=ContextBoundTransport(context,false,{false},{error("No reconnect")}).execute{get(client,peer)}
                if(!result.isSuccessful){assertNull(result.observation);throw ApiError.Http(HttpFailure(result.code,result.text))};context.check();final=result;result.text
            }.execute()
            assertEquals("{}",body);assertEquals(2,peer.count.get());assertEquals(1,rotations)
            assertTrue(final!!.observedClock(peer.url).upperMillis()<java.time.Instant.parse("2026-11-17T00:00:00Z").toEpochMilli())
        }finally{client.stop()}}
    }
    @Test fun `actual profile locked intermediate Date is discarded before explicit PIN retry`()=runBlocking {
        WatchingPeer(listOf(WatchingReply(status=403,date="Tue, 17 Nov 2026 23:43:46 GMT",body="{\"code\":\"profile_locked\",\"profileId\":\"synthetic\"}"),WatchingReply())).use{peer->val client=client();try{
            val context=RequestContext({0L},{}).capture(0);var prompt=0;var accepted:HttpResult?=null
            suspend fun attempt()=RequestAuthorization(context,{"synthetic-token"},{false},{error("No refresh")},{error("No signout")}){val response=get(client,peer);context.check();if(!response.isSuccessful){assertNull(response.observation);throw ApiError.Http(HttpFailure(response.code,response.text))};accepted=response;response.text}.execute()
            val body=try{attempt()}catch(error:ApiError){if(!error.isProfileLocked)throw error;context.check();prompt++;attempt()}
            assertEquals("{}",body);assertEquals(1,prompt);assertEquals(2,peer.count.get());assertTrue(accepted!!.observedClock(peer.url).upperMillis()<java.time.Instant.parse("2026-11-17T00:00:00Z").toEpochMilli())
        }finally{client.stop()}}
    }
    @Test fun `transport reconnect uses final physical Call clock and cannot retarget old context`()=runBlocking {
        WatchingPeer(listOf(WatchingReply(drop=true),WatchingReply())).use{peer->val client=client();try{
            var epoch=0L;val contexts=RequestContext({epoch},{epoch++});val context=contexts.capture(0);var reconnect=0
            val result=ContextBoundTransport(context,true,{reconnect++;true},{error("No failure callback")}).execute{get(client,peer)}
            assertEquals(2,peer.count.get());assertEquals(1,reconnect);assertNotNull(result.observedClock(peer.url))
        }finally{client.stop()}}
    }
    @Test fun `late accepted HTTP after original scope change cannot publish response metadata`()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        WatchingPeer(listOf(WatchingReply(entered=entered,release=release))).use{peer->val client=client();try{
            var epoch=0L;val contexts=RequestContext({epoch},{epoch++});val context=contexts.capture(0);val work=async(Dispatchers.IO){runCatching{ContextBoundTransport(context,false,{false},{}).execute{get(client,peer)}}}
            assertTrue(entered.await(3,TimeUnit.SECONDS));contexts.changeProfile{};release.countDown();assertTrue(work.await().exceptionOrNull() is CancellationException)
        }finally{release.countDown();client.stop()}}
    }
    @Test fun `original coroutine cancel closes actual body read before unrelated sibling completes`()=runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        WatchingPeer(listOf(WatchingReply(entered=entered,release=release,body="{}"))).use{peer->val client=client();val parent=Job();val scope=CoroutineScope(Dispatchers.IO+parent);val siblingGate=CompletableDeferred<Unit>();val sibling=scope.launch{withContext(NonCancellable){siblingGate.await()}};try{
            val call=client.newCall(Request.Builder().url(peer.url).build());val done=CompletableDeferred<Unit>();scope.launch{try{call.await(true)}finally{done.complete(Unit)}}
            assertTrue(entered.await(3,TimeUnit.SECONDS));parent.cancel();withTimeout(3000){done.await()};assertTrue(call.isCanceled());assertFalse(sibling.isCompleted)
        }finally{release.countDown();siblingGate.complete(Unit);sibling.join();scope.cancel();client.stop()}}
    }
    @Test fun `unchanged original delayed Date expires instead of renewing original pool TTL`()=runBlocking {
        val root=WatchingFixture.root();val pool=root.getValue("pool").let{it as kotlinx.serialization.json.JsonObject}
        val at=java.time.Instant.parse("2026-11-16T23:43:46Z").toEpochMilli();val expiry=java.time.Instant.ofEpochMilli(at+1500).toString()
        val changed=kotlinx.serialization.json.JsonObject(root+("pool" to kotlinx.serialization.json.JsonObject(pool+("expiresAt" to kotlinx.serialization.json.JsonPrimitive(expiry)))))
        WatchingPeer(listOf(WatchingReply(body=changed.toString(),delayHeader=650,delayBody=100))).use{peer->val client=client();try{
            val response=get(client,peer);assertTrue(runCatching{decodeCachedWatchingPage(response.text,response.observedClock(peer.url))}.isFailure)
        }finally{client.stop()}}
    }
    @Test fun `actual cache query builder reaches only the captured profile path and requested options`()=runBlocking {
        val list=WatchingFixture.list();val selected=BrowseOptions(kind=BrowseKind.SHOWS,originalLanguages=setOf("fr"),includeUnknownOriginalLanguage=true)
        val original=cachedWatchingRequest("profile-a",list,2,0,selected)
        WatchingPeer(listOf(WatchingReply(body=WatchingFixture.wire("show-episode")))).use{peer->val client=client();try{
            val url=peer.url.newBuilder().encodedPath(original.path).query(null).apply{original.query.toSortedMap().forEach{(k,v)->addQueryParameter(k,v)}}.build()
            val stamp=RequestContext({0L},{}).capture(0)
            val result=ContextBoundTransport(stamp,false,{false},{error("No recovery")}).execute{client.newCall(Request.Builder().url(url).build()).await(true)}
            assertEquals("GET ${url.encodedPath}?${url.encodedQuery} HTTP/1.1",peer.lines.single())
            assertEquals("fra",url.queryParameter("originalLanguages"));assertEquals("series",url.queryParameter("filterMediaType"));assertEquals(1,decodeCachedWatchingPage(result.text,result.observedClock(url),2).total)
        }finally{client.stop()}}
    }
    @Test fun `context changed during actual auth rotation or PIN prompt prevents second HTTP and clock adoption`()=runBlocking {
        for (pin in listOf(false,true)) {
            WatchingPeer(listOf(WatchingReply(status=if(pin)403 else 401,body=if(pin)"{\"code\":\"profile_locked\"}" else "{\"code\":\"unauthorized\"}"))).use{peer->val client=client();try{
                var epoch=0L;val contexts=RequestContext({epoch},{epoch++});val context=contexts.capture(0);var accepted:HttpResult?=null
                suspend fun attempt()=RequestAuthorization(context,{"synthetic"},{false},{contexts.changeProfile{};ApiClient.RefreshOutcome.REFRESHED},{error("No signout")}){
                    val result=get(client,peer);context.check();if(!result.isSuccessful)throw ApiError.Http(HttpFailure(result.code,result.text));accepted=result;result.text
                }.execute()
                val outcome=runCatching{try{attempt()}catch(error:ApiError){if(!error.isProfileLocked)throw error;contexts.changeProfile{};context.check();attempt()}}
                assertTrue(outcome.exceptionOrNull() is CancellationException);assertEquals(1,peer.count.get());assertNull(accepted)
            }finally{client.stop()}}
        }
    }

}
