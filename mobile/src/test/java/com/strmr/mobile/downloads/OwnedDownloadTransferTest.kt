package com.strmr.mobile.downloads

import com.strmr.tv.api.DetailsTarget
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real file writes and the production transfer consume a deliberately blocked HTTP body. */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnedDownloadTransferTest {
    @get:Rule val temporary=TemporaryFolder()
    private val owner=DownloadOwner.Known("configured:"+"a".repeat(64),"synthetic-account","synthetic-profile")
    private class Blocked:InputStream() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        private var read=false
        override fun read():Int=error("Array reads expected")
        override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
            entered.countDown();check(release.await(5,TimeUnit.SECONDS))
            if(read)return -1;read=true;val data="OLD".toByteArray();data.copyInto(bytes,offset);return data.size
        }
    }
    private fun body(input:InputStream)=object:ResponseBody() {
        private val buffered=input.source().buffer()
        override fun contentType():MediaType?=null
        override fun contentLength()=3L
        override fun source()=buffered
    }
    private suspend fun barrier(latch:CountDownLatch)=withContext(Dispatchers.IO) {check(latch.await(5,TimeUnit.SECONDS))}
    private suspend fun TestScope.waitUntil(test:()->Boolean) {
        repeat(200) {runCurrent();if(test())return;withContext(Dispatchers.IO){Thread.sleep(5)}}
        fail("Synthetic transfer did not settle")
    }
    private inner class Fixture(val test:TestScope) {
        var context:OfflineContext?=OfflineContext(owner,1)
        val row=DownloadItem.create(DetailsTarget("tmdb:movie:1","Synthetic",true),null,"/synthetic/file.mkv",null,3,owner=owner)
        val directory=temporary.newFolder()
        val blocked=Blocked();val calls=AtomicInteger()
        var removed=0
        fun verify(ctx:OfflineContext,item:DownloadItem?) {if(context!=ctx || item!=null && item.owner!=ctx.owner)throw CancellationException("Synthetic ownership changed")}
        val files=OwnedDownloadFiles(directory,object:DownloadFileGuard {override fun<T> use(context:OfflineContext,item:DownloadItem,action:()->T):T {verify(context,item);return action()}})
        val http=OkHttpClient.Builder().addInterceptor {chain->
            val count=calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                .body(body(if(count==1)blocked else ByteArrayInputStream("NEW".toByteArray()))).build()
        }.build()
        val manager=OwnedDownloadManager(test.backgroundScope,{context},::verify,{listOf(row)},{_,_->},
            {item,ctx,progress->DownloadTransfer(item,ctx,progress,files,::verify,{_,captured,range->
                verify(captured,row)
                val request=Request.Builder().url("http://127.0.0.1/synthetic").apply {range?.let {header("Range",it)}}.build()
                OwnedDownloadHttp(request,request.url,emptyMap()){verify(captured,row)}
            },{error("Unexpected refresh")},http).run()},
            {item,ctx->verify(ctx,item);files.remove(item,ctx);removed++},{item,ctx->files.bytes(item,ctx)})
        val conditions=DownloadQueue.Conditions(1,false,true,true,1)
        fun start() {manager.start();manager.process(conditions)}
    }
    @Test fun `pause and immediate resume wait for old reader and never append its late bytes`()=runTest {
        val f=Fixture(this);try {
            f.start();runCurrent();barrier(f.blocked.entered)
            f.manager.pause(f.row.id);f.manager.resume(f.row.id);runCurrent()
            assertEquals(1,f.calls.get());assertFalse(f.files.file(f.row,f.context!!).exists())
            f.blocked.release.countDown();waitUntil {f.manager.item(f.row.id)?.isCompleted==true}
            assertEquals(2,f.calls.get());assertEquals("NEW",f.files.file(f.row,f.context!!).readText());assertEquals(3L,f.manager.item(f.row.id)!!.bytesWritten)
        }finally {f.blocked.release.countDown();f.http.dispatcher.executorService.shutdownNow()}
    }
    @Test fun `delete waits for old reader before removing files and leaves no late recreation`()=runTest {
        val f=Fixture(this);try {
            f.start();runCurrent();barrier(f.blocked.entered)
            f.manager.delete(listOf(f.row.id));runCurrent();assertEquals(0,f.removed)
            f.blocked.release.countDown();waitUntil {f.removed==1}
            assertNull(f.manager.item(f.row.id));assertFalse(java.io.File(f.directory,f.row.fileName).exists());assertFalse(java.io.File(f.directory,f.row.id+".part").exists());assertEquals(1,f.calls.get())
        }finally {f.blocked.release.countDown();f.http.dispatcher.executorService.shutdownNow()}
    }
    @Test fun `profile change during read preserves partial file and denies late finish`()=runTest {
        val f=Fixture(this);try {
            f.start();runCurrent();barrier(f.blocked.entered)
            f.context=OfflineContext(owner.copy(profileId="synthetic-other"),2);f.manager.process(f.conditions)
            f.blocked.release.countDown();waitUntil {f.http.dispatcher.runningCallsCount()==0}
            f.context=OfflineContext(owner,3);f.manager.process(f.conditions);runCurrent()
            assertEquals(DownloadItem.Status.PAUSED,f.manager.item(f.row.id)!!.status)
            assertFalse(java.io.File(f.directory,f.row.fileName).exists());assertTrue(java.io.File(f.directory,f.row.id+".part").isFile);assertEquals(0L,java.io.File(f.directory,f.row.id+".part").length())
        }finally {f.blocked.release.countDown();f.http.dispatcher.executorService.shutdownNow()}
    }
}
