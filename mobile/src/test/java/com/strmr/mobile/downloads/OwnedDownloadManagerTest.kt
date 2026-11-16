package com.strmr.mobile.downloads

import com.strmr.tv.api.DetailsTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedDownloadManagerTest {
    private val owner=DownloadOwner.Known("configured:"+"a".repeat(64),"synthetic-account-a","synthetic-profile-a")
    private fun item(owner:DownloadOwner?=this.owner,status:DownloadItem.Status=DownloadItem.Status.PAUSED)=DownloadItem.create(DetailsTarget("tmdb:movie:1","Synthetic",true),null,"/synthetic/file.mkv",null,10).copy(owner=owner,status=status)
    private class Fixture(val scope:CoroutineScope,val initial:List<DownloadItem>,owner:DownloadOwner.Known) {
        var context:OfflineContext?=OfflineContext(owner,1)
        var transfers=0;var completions=0;var failures=0;var deletes=0
        val saves=mutableListOf<Pair<Long,List<DownloadItem>>>()
        var perform:suspend(DownloadItem,OfflineContext,(Long,Long?)->Unit)->DownloadTransfer.Outcome={_,_,_->transfers++;DownloadTransfer.Outcome.Completed(10)}
        var remove:suspend(DownloadItem,OfflineContext)->Unit={row,ctx->verify(ctx,row);deletes++}
        var count:suspend(DownloadItem,OfflineContext)->Long={_,_->10L}
        fun verify(ctx:OfflineContext,row:DownloadItem?) {if(ctx!=context || row!=null && row.owner!=ctx.owner)throw CancellationException("Synthetic identity changed")}
        val manager=OwnedDownloadManager(scope,{context},::verify,{initial},{revision,rows->saves+=revision to rows},{row,ctx,progress->perform(row,ctx,progress)},{row,ctx->remove(row,ctx)},{row,ctx->count(row,ctx)},completed={completions++},failed={failures++},now={100})
        val conditions=DownloadQueue.Conditions(1,false,true,true,100)
        fun start() {manager.start();manager.process(conditions)}
    }
    @Test fun `legacy foreign unknown and unavailable records never enter queue or deletion`()=runTest {
        val own=item();val foreign=item(owner.copy(profileId="synthetic-b"),DownloadItem.Status.DOWNLOADING);val legacy=item(null,DownloadItem.Status.PENDING)
        val f=Fixture(backgroundScope,listOf(own,foreign,legacy),owner);f.start()
        assertEquals(listOf(own),f.manager.items.value);assertNull(f.manager.item(foreign.id));assertNull(f.manager.item(legacy.id))
        f.manager.resume(foreign.id);f.manager.resume(legacy.id);f.manager.delete(listOf(foreign.id,legacy.id));runCurrent();assertEquals(0,f.transfers);assertEquals(0,f.deletes)
        f.manager.persistNow();runCurrent();assertEquals(listOf(own,foreign,legacy),f.saves.last().second)
        f.context=null;f.manager.process(f.conditions);assertTrue(f.manager.items.value.isEmpty());assertNull(f.manager.item(own.id))
    }
    @Test fun `new queue record belongs to captured owner and collision never overwrites foreign record`()=runTest {
        val foreign=item(owner.copy(accountId="synthetic-b"));val f=Fixture(backgroundScope,listOf(foreign),owner);f.start()
        val collision=foreign.copy(owner=owner)
        assertTrue(runCatching {f.manager.add(collision,f.context!!)}.isFailure)
        val own=item(status=DownloadItem.Status.PENDING);f.manager.add(own,f.context!!);runCurrent()
        assertEquals(1,f.transfers);assertEquals(1,f.completions);assertEquals(foreign,f.saves.last().second.first {it.id==foreign.id})
    }
    @Test fun `late transfer and progress after A B A cannot complete or notify replacement epoch`()=runTest {
        val own=item(status=DownloadItem.Status.PENDING);val f=Fixture(backgroundScope,listOf(own),owner)
        val finish=CompletableDeferred<Unit>();var progress:((Long,Long?)->Unit)?=null
        f.perform={_,_,report->f.transfers++;progress=report;withContext(NonCancellable){finish.await()};DownloadTransfer.Outcome.Completed(10)}
        f.start();runCurrent();assertEquals(1,f.transfers)
        f.context=OfflineContext(owner.copy(profileId="synthetic-b"),2);f.manager.process(f.conditions)
        f.context=OfflineContext(owner,3);f.manager.process(f.conditions);progress!!(999,1000);finish.complete(Unit);runCurrent()
        assertEquals(DownloadItem.Status.PAUSED,f.manager.item(own.id)!!.status);assertEquals(0L,f.manager.item(own.id)!!.bytesWritten);assertEquals(0,f.completions);assertEquals(0,f.failures)
    }
    @Test fun `late delete cannot delete files or remove metadata in replacement context`()=runTest {
        val own=item();val f=Fixture(backgroundScope,listOf(own),owner);val ready=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        f.remove={row,ctx->ready.complete(Unit);finish.await();f.verify(ctx,row);f.deletes++}
        f.start();f.manager.delete(listOf(own.id));runCurrent();assertTrue(ready.isCompleted)
        f.context=OfflineContext(owner.copy(profileId="synthetic-b"),2);f.manager.process(f.conditions);finish.complete(Unit);runCurrent()
        assertEquals(0,f.deletes);f.context=OfflineContext(owner,3);f.manager.process(f.conditions);assertNotNull(f.manager.item(own.id))
    }
    @Test fun `usage projection hides previous owner synchronously before context observer runs`()=runTest {
        val own=item();val f=Fixture(backgroundScope,listOf(own),owner);f.start();f.manager.refreshUsage();runCurrent();assertEquals(10L,f.manager.currentBytes())
        f.context=OfflineContext(owner.copy(accountId="synthetic-b"),2)
        assertEquals(0L,f.manager.currentBytes());assertNull(f.manager.item(own.id))
        f.context=OfflineContext(owner,3);assertEquals(0L,f.manager.currentBytes())
    }
    @Test fun `deferred byte count from old epoch cannot publish after context has returned`()=runTest {
        val own=item();val f=Fixture(backgroundScope,listOf(own),owner);val finish=CompletableDeferred<Long>()
        f.count={_,_->finish.await()};f.start();f.manager.refreshUsage();runCurrent()
        f.context=OfflineContext(owner.copy(profileId="synthetic-b"),2);f.context=OfflineContext(owner,3);finish.complete(99);runCurrent();assertEquals(0L,f.manager.currentBytes())
    }
    @Test fun `cold or restricted background startup makes no transfer and preserves raw records`()=runTest {
        val own=item(status=DownloadItem.Status.PENDING);val f=Fixture(backgroundScope,listOf(own),owner);f.context=null;f.start();runCurrent()
        assertEquals(0,f.transfers);assertTrue(f.manager.items.value.isEmpty());f.manager.persistNow();runCurrent();assertEquals(listOf(own),f.saves.last().second)
    }
    @Test fun `pending intent is paused on A B A instead of silently starting under a new epoch`()=runTest {
        val own=item(status=DownloadItem.Status.PENDING);val foreign=item(owner.copy(profileId="synthetic-b"),DownloadItem.Status.PENDING);val f=Fixture(backgroundScope,listOf(own,foreign),owner)
        f.manager.start();f.manager.process(f.conditions.copy(serverReachable=false));runCurrent();assertEquals(0,f.transfers)
        f.context=null;f.manager.process(f.conditions);f.context=OfflineContext(owner,3);f.manager.process(f.conditions);runCurrent()
        assertEquals(0,f.transfers);assertEquals(DownloadItem.Status.PAUSED,f.manager.item(own.id)!!.status)
        f.manager.persistNow();runCurrent();assertEquals(foreign,f.saves.last().second.first {it.id==foreign.id})
    }
    @Test fun `failed load is never followed by persistence of an empty ledger`()=runTest {
        var saves=0
        val manager=OwnedDownloadManager(backgroundScope,{null},{_,_->},{throw DownloadAccessError()},{_,_->saves++},{_,_,_->error("Unexpected transfer")},{_,_->},{_,_->0})
        assertTrue(runCatching {manager.start()}.isFailure);manager.persistNow();runCurrent();assertEquals(0,saves)
    }
}
