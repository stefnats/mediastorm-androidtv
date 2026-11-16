package com.strmr.tv.sources

import androidx.compose.runtime.*
import com.strmr.tv.api.*
import com.strmr.tv.library.ProfileDataContext
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** One Sources visit owns its requests, cursor, health and actions. Filters never start another search. */
class SourceSearchState(
    parent:CoroutineScope,val title:DetailsTarget,val episode:Episode?,val context:Long=ProfileDataContext.current,
    private val transport:SourceSearchTransport=ApiSourceSearchTransport,
    private val now:()->Instant=Instant::now,
    private val clientIdentity:()->String={com.strmr.tv.app.Config.clientId},
) {
    private val client=clientIdentity()
    val target=SourceSearchTarget.from(title,episode)
    private val owner=SupervisorJob(parent.coroutineContext[Job])
    private val scope=CoroutineScope(parent.coroutineContext+owner)
    private var observer:Job?=null
    private var request:Job?=null
    private var paging:Job?=null
    private var expiry:Job?=null
    private val healthJobs=mutableListOf<Job>()
    private val health=mutableStateMapOf<Int,SourceHealth>()
    private val consumedCursors=mutableSetOf<String>()
    var sourceGeneration by mutableLongStateOf(0L);private set
    private var viewGeneration by mutableLongStateOf(0L)
    private var manualGeneration by mutableLongStateOf(0L)
    private var page by mutableStateOf<SourceSearchPage?>(null)
    var rows by mutableStateOf<List<SourceSearchRow>>(emptyList());private set
    var supported by mutableStateOf(false);private set
    var vodDenied by mutableStateOf<VodAccessDenial?>(null);private set
    var loading by mutableStateOf(false);private set
    var loadingMore by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null);private set
    var pageError by mutableStateOf<String?>(null);private set
    var requiresReload by mutableStateOf(false);private set
    var expired by mutableStateOf(false);private set
    var metadataChanged by mutableStateOf(false);private set
    var draft by mutableStateOf(SourceFilters());private set
    val applied get()=page?.filters ?: SourceFilters()
    val total get()=page?.total ?: rows.size
    val matchedTotal get()=page?.matchedTotal ?: 0
    val unknownTotal get()=page?.unknownTotal ?: 0
    val truncated get()=page?.coverage?.truncated==true
    val hasMore get()=supported && !metadataChanged && liveView() && page?.nextCursor!=null
    val expiredNow get()=expired || page?.let {!now().isBefore(it.expiresAt)}==true
    val isCurrent get()=owner.isActive && context==ProfileDataContext.current && clientIdentity()==client
    val filterPending get()=supported && draft!=applied
    init {observer=scope.launch {ProfileDataContext.changes.first {it!=context};dispose()}}
    private suspend fun check() {currentCoroutineContext().ensureActive();ProfileDataContext.requireCurrent(context);if(!isCurrent)throw CancellationException("Sources page closed")}
    private fun liveView()=isCurrent && vodDenied==null && !expired && page?.let {now().isBefore(it.expiresAt)}!=false

    class Selection internal constructor(val row:SourceSearchRow,internal val source:Long,internal val view:Long)
    fun selection(row:SourceSearchRow):Selection? = if(liveView() && !metadataChanged && !loading && rows.any {it===row})Selection(row,sourceGeneration,viewGeneration)else null
    fun accepts(selection:Selection)=liveView() && !metadataChanged && !loading && selection.source==sourceGeneration && selection.view==viewGeneration && rows.any {it===selection.row}
    fun use(selection:Selection,action:(SourceSearchRow)->Unit) {
        if(accepts(selection))action(selection.row)
    }
    class ManualSelection internal constructor(val result:SourceResult,internal val source:Long,internal val generation:Long,internal val validUntil:Instant?)
    fun beginManual(row:SourceSearchRow):ManualSelection? {
        if(selection(row)==null)return null
        manualGeneration++
        return ManualSelection(row.result,sourceGeneration,manualGeneration,if(supported)now().plusSeconds(900)else null)
    }
    fun acceptsManual(selection:ManualSelection)=isCurrent && !requiresReload && selection.source==sourceGeneration && selection.generation==manualGeneration &&
        (selection.validUntil==null || now().isBefore(selection.validUntil)) && selection.result.responseScope==page?.snapshotId
    fun useManual(selection:ManualSelection,action:(SourceResult)->Unit) {
        if(acceptsManual(selection)){manualGeneration++;action(selection.result)}
    }
    fun endManual() {manualGeneration++}
    val viewId get()=page?.viewId ?: "legacy"
    fun manualLibrary(action:()->Unit) {if(isCurrent && vodDenied==null)action()}
    fun healthFor(row:SourceSearchRow)=if(isCurrent)health[row.sourceIndex] ?: row.result.health else null
    fun edit(filters:SourceFilters) {if(isCurrent && supported && filters.valid())draft=filters}
    fun clearDraft() {if(isCurrent && supported)draft=SourceFilters()}
    fun probeChanged(result:SourceResult) {
        if(isCurrent && supported && !requiresReload && result.responseScope==page?.snapshotId) {
            metadataChanged=true;viewGeneration++;paging?.cancel();loadingMore=false
        }
    }
    fun probeFailed(result:SourceResult,error:Exception) {
        if(!isCurrent || result.responseScope!=page?.snapshotId || !supported && error.vodDenial==null)return
        if(error.vodDenial!=null || unavailable(error)) {
            vodDenied=error.vodDenial
            if(vodDenied!=null)rows=emptyList()
            requiresReload=true;expired=true;metadataChanged=true;viewGeneration++;manualGeneration++
            this.error=error.vodDenial?.message ?: message(error)
            if(vodDenied!=null) {healthJobs.forEach {it.cancel()};paging?.cancel();health.clear()}
        }
    }

    /** Explicit initial open / Reload only. An expired view never calls this by itself. */
    fun reload() {
        if(!isCurrent || loading || vodDenied!=null)return
        val wanted=if(supported)draft else SourceFilters()
        request?.cancel();paging?.cancel();expiry?.cancel();healthJobs.forEach {it.cancel()};healthJobs.clear();health.clear()
        sourceGeneration++;viewGeneration++;manualGeneration++;val source=sourceGeneration
        page=null;rows=emptyList();supported=false;expired=false;requiresReload=false;metadataChanged=false
        loading=true;loadingMore=false;error=null;pageError=null;consumedCursors.clear()
        request=scope.launch {
            val attempt=VodDenialEvents.begin(target.titleId,context)
            try {
                check();require(target.valid())
                val reply=transport.search(title,episode,context)
                check();if(source!=sourceGeneration)return@launch
                when(reply) {
                    is SourceSearchReply.Legacy->{rows=reply.rows;draft=SourceFilters();supported=false;startHealth(reply.rows,null,source)}
                    is SourceSearchReply.Supported->{
                        validate(reply.page,SourceFilters())
                        supported=true;accept(reply.page);draft=wanted
                        startHealth(reply.page.rows,reply.page.sourceHealthToken,source)
                        if(wanted!=reply.page.filters) {
                            val filtered=transport.page(reply.page.snapshotId,wanted,null,context)
                            check();if(source!=sourceGeneration)return@launch
                            validate(filtered,wanted,reply.page.snapshotId);accept(filtered)
                        }
                    }
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(isCurrent && source==sourceGeneration){vodDenied=e.vodDenial;e.vodDenial?.let {VodDenialEvents.reject(attempt,it)};error=e.vodDenial?.message ?: message(e);requiresReload=true;rows=emptyList()}}
            finally {if(isCurrent && source==sourceGeneration)loading=false}
        }
    }

    fun apply() {
        val current=page ?: return
        if(!isCurrent || !supported || loading || requiresReload || !draft.valid())return
        request?.cancel();paging?.cancel();viewGeneration++;val view=viewGeneration;val source=sourceGeneration;val filters=draft
        rows=emptyList();loading=true;loadingMore=false;error=null;pageError=null;metadataChanged=false;consumedCursors.clear()
        request=scope.launch {
            val attempt=VodDenialEvents.begin(target.titleId,context)
            try {
                check()
                val loaded=transport.page(current.snapshotId,filters,null,context)
                check();if(source!=sourceGeneration || view!=viewGeneration)return@launch
                validate(loaded,filters,current.snapshotId);accept(loaded)
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(isCurrent && source==sourceGeneration && view==viewGeneration){vodDenied=e.vodDenial;e.vodDenial?.let {VodDenialEvents.reject(attempt,it)};error=e.vodDenial?.message ?: message(e);requiresReload=vodDenied!=null || unavailable(e);expired=viewChanged(e);if(!requiresReload && !expired)scheduleExpiry(current)}}
            finally {if(isCurrent && source==sourceGeneration && view==viewGeneration)loading=false}
        }
    }
    fun loadMore() {
        val current=page ?: return;val cursor=current.nextCursor ?: return
        if(!liveView() || metadataChanged || loading || loadingMore || !supported || cursor in consumedCursors)return
        val source=sourceGeneration;val view=viewGeneration;loadingMore=true;pageError=null
        paging=scope.launch {
            val attempt=VodDenialEvents.begin(target.titleId,context)
            try {
                check();val loaded=transport.page(current.snapshotId,current.filters,cursor,context)
                check();if(source!=sourceGeneration || view!=viewGeneration)return@launch
                validate(loaded,current.filters,current.snapshotId,current.viewId)
                require(loaded.expiresAt==current.expiresAt && loaded.total==current.total && loaded.matchedTotal==current.matchedTotal && loaded.unknownTotal==current.unknownTotal && loaded.coverage==current.coverage)
                require(loaded.nextCursor!=cursor && (loaded.nextCursor==null || loaded.nextCursor !in consumedCursors))
                val indices=rows.map {it.sourceIndex}.toSet();require(loaded.rows.none {it.sourceIndex in indices})
                val count=rows.size+loaded.rows.size
                require(count<=loaded.total && if(loaded.nextCursor==null)count==loaded.total else count<loaded.total)
                consumedCursors+=cursor;rows=rows+loaded.rows;page=loaded;scheduleExpiry(loaded)
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(isCurrent && source==sourceGeneration && view==viewGeneration){vodDenied=e.vodDenial;e.vodDenial?.let {VodDenialEvents.reject(attempt,it)};pageError=if(vodDenied!=null)vodDenied!!.message else if(e is IllegalArgumentException)"Filter data changed. Apply filters again." else message(e);if(vodDenied!=null || unavailable(e)){requiresReload=true;expired=true}else if(viewChanged(e) || e is IllegalArgumentException){expired=true}}}
            finally {if(isCurrent && source==sourceGeneration && view==viewGeneration)loadingMore=false}
        }
    }
    fun retry() {if(requiresReload || page==null)reload()else if(expiredNow)apply()else if(pageError!=null)loadMore()else apply()}
    private fun validate(value:SourceSearchPage,filters:SourceFilters,snapshot:String?=null,view:String?=null) {
        require(value.target==target && value.filters==filters && (snapshot==null || snapshot==value.snapshotId) && (view==null || view==value.viewId))
        require(now().isBefore(value.expiresAt) && !value.expiresAt.isAfter(now().plus(Duration.ofMinutes(15))))
    }
    private fun accept(value:SourceSearchPage) {
        require(if(value.nextCursor==null)value.rows.size==value.total else value.rows.size<value.total)
        page=value;rows=value.rows;expired=false;requiresReload=false;metadataChanged=false;error=null;pageError=null;scheduleExpiry(value)
    }
    private fun scheduleExpiry(value:SourceSearchPage) {
        expiry?.cancel();val source=sourceGeneration;val view=viewGeneration
        expiry=scope.launch {
            delay(Duration.between(now(),value.expiresAt).toMillis().coerceAtLeast(0))
            if(isCurrent && source==sourceGeneration && view==viewGeneration && page?.viewId==value.viewId){expired=true}
        }
    }
    private fun startHealth(initial:List<SourceSearchRow>,token:String?,source:Long) {
        for(row in initial)row.result.health?.let {health[row.sourceIndex]=it}
        val tokens=(initial.mapNotNull {it.result.health?.takeIf {h->h.state=="pending"}?.token}+listOfNotNull(token)).toSet()
        for(t in tokens)healthJobs+=scope.launch {
            var attempt=VodDenialEvents.begin(target.titleId,context)
            try {
                while(isCurrent && vodDenied==null && source==sourceGeneration) {
                    check();attempt=VodDenialEvents.begin(target.titleId,context);val update=transport.health(t,context);check();if(source!=sourceGeneration)return@launch
                    for(entry in update.results)if(initial.any {it.sourceIndex==entry.index})health[entry.index]=entry.health
                    if(update.complete)break
                }
            } catch(e:CancellationException){throw e}
            catch(error:Exception){if(isCurrent && source==sourceGeneration && error.vodDenial!=null) {VodDenialEvents.reject(attempt,error.vodDenial!!);initial.firstOrNull()?.let {probeFailed(it.result,error)}}}
            finally {if(isCurrent && source==sourceGeneration)health.filterValues {it.state=="pending" && it.token==t}.keys.toList().forEach {health[it]=SourceHealth("unknown")}}
        }
    }
    private fun viewChanged(error:Exception)=error is ApiError.Http && error.response.code in setOf("source_view_expired","source_view_changed")
    private fun unavailable(error:Exception)=error is ApiError.Http && !viewChanged(error) && error.response.status in setOf(401,403,404,409,410)
    private fun message(error:Exception)=when {
        viewChanged(error)->"Filter data changed. Apply filters again."
        unavailable(error)->"Source results expired or changed. Reload the source list."
        else->"Source results could not be loaded. Try again."
    }
    fun dispose() {owner.cancel();observer?.cancel();rows=emptyList();health.clear();sourceGeneration++;viewGeneration++;manualGeneration++}
}
