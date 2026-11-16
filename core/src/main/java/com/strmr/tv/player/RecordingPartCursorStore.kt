package com.strmr.tv.player

import android.content.Context
import com.strmr.tv.api.ApiClient
import com.strmr.tv.app.App
import com.strmr.tv.app.AppSession
import com.strmr.tv.app.NetworkRoutes
import com.strmr.tv.live.multiscreenNamespace
import com.strmr.tv.live.unpairedMultiscreenNamespace
import com.strmr.tv.remote.RemoteAccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Local part position only. The signature correlates a closed snapshot, never permission or a media timeline. */
@Serializable
data class RecordingPartCursor(val recordingId:String,val partIndex:Int,val localPositionMs:Long,val closedSignature:String,val ended:Boolean)

object RecordingPartCursorStore {
    private val prefs by lazy {App.context.getSharedPreferences("strmr.recording-parts",Context.MODE_PRIVATE)}
    private val cursors by lazy {RecordingPartCursors(
        namespace={owner->unpairedMultiscreenNamespace(
            savedPair={NetworkRoutes.isPaired},unreadablePair={RemoteAccess.pairingUnreadable},
            identity={multiscreenNamespace(NetworkRoutes.savedServerUrl,ApiClient.session?.accountId,owner.profileId,
                AppSession.isRemote.value || NetworkRoutes.isHomeDirect.value || ApiClient.remoteCredential!=null)?.replace("last.v2.","cursor.v1.")},
        )},
        read={prefs.getString(it,null)},write={key,value->prefs.edit().putString(key,value).apply()},
    )}
    fun positionLabel(recording:com.strmr.tv.live.Recording,expected:Long):String? {
        val snapshot=RecordingPartSnapshot.capture(recording) ?: return null
        return try {
            val owner=PlaybackSessionContext.capture(expected)
            val cursor=read(owner,snapshot) ?: return null
            val offset=snapshot.parts.indexOfFirst {it.index==cursor.partIndex}
            if(cursor.ended) snapshot.parts.getOrNull(offset+1)?.let {"Saved position: Part ${it.index} from start"}
            else "Saved position: Part ${cursor.partIndex} · ${cursor.localPositionMs/1000}s into this part"
        } catch (_:kotlinx.coroutines.CancellationException) {null}
    }
    internal fun read(owner:PlaybackSessionContext,snapshot:RecordingPartSnapshot)=cursors.read(owner,snapshot)
    internal fun write(owner:PlaybackSessionContext,snapshot:RecordingPartSnapshot,cursor:RecordingPartCursor)=cursors.write(owner,snapshot,cursor)
}

/** Actual memory/persistence adapter; unknown or paired identity stays memory-only in the current epoch. */
internal class RecordingPartCursors(
    private val namespace:(PlaybackSessionContext)->String?,
    private val read:(String)->String?,
    private val write:(String,String)->Unit,
) {
    private val json=Json {ignoreUnknownKeys=true}
    private var epoch:Long?=null
    private val memory=linkedMapOf<String,RecordingPartCursor>()
    private fun <T> local(action:()->T):T? = try {action()} catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled} catch(_:Exception){null}
    private fun prepare(owner:PlaybackSessionContext) {if(epoch!=owner.epoch){memory.clear();epoch=owner.epoch}}
    private fun key(owner:PlaybackSessionContext,id:String):String? = namespace(owner)?.takeIf {Regex("cursor\\.v1\\.[0-9a-f]{64}").matches(it)}?.let {"$it.$id"}
    fun read(owner:PlaybackSessionContext,snapshot:RecordingPartSnapshot):RecordingPartCursor? = owner.use {
        prepare(owner)
        val stored=memory[snapshot.recording.id] ?: key(owner,snapshot.recording.id)?.let {key->
            local {read(key)}?.let {runCatching {json.decodeFromString<RecordingPartCursor>(it)}.getOrNull()}
        }
        stored?.takeIf {snapshot.accepts(it)}
    }
    fun write(owner:PlaybackSessionContext,snapshot:RecordingPartSnapshot,cursor:RecordingPartCursor)=owner.use {
        prepare(owner)
        if(snapshot.accepts(cursor)) {
            memory[snapshot.recording.id]=cursor
            while(memory.size>128)memory.remove(memory.keys.first())
            key(owner,snapshot.recording.id)?.let {local {write(it,json.encodeToString(cursor))}}
        }
        Unit
    }
}
