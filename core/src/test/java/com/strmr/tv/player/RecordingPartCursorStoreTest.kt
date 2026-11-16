package com.strmr.tv.player

import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.live.multiscreenNamespace
import com.strmr.tv.live.unpairedMultiscreenNamespace
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual local cursor storage adapter; SecureStore and Android SharedPreferences wiring is source-only. */
class RecordingPartCursorStoreTest {
    private lateinit var owner:PlaybackSessionContext
    private val snapshot get()=RecordingPartSnapshot.capture(queueRecording())!!
    private fun cursor(index:Int=5,position:Long=5432,ended:Boolean=false)=RecordingPartCursor("queue",index,position,snapshot.closedSignature(index) ?: "absent",ended)
    private fun namespace(server:String="https://typed.invalid/proxy",account:String="account",profile:String=owner.profileId)=multiscreenNamespace(server.toHttpUrl(),account,profile,false)!!.replace("last.v2.","cursor.v1.")
    @Before fun before(){ProfileDataContext.invalidate();owner=PlaybackSessionContext.capture()}
    @After fun after(){ProfileDataContext.invalidate()}
    @Test fun `unknown identity stores current owner cursor in memory without disk IO`() {
        var reads=0;var writes=0;val storage=RecordingPartCursors({null},{reads++;null},{_,_->writes++})
        storage.write(owner,snapshot,cursor());assertEquals(cursor(),storage.read(owner,snapshot));assertEquals(0,reads);assertEquals(0,writes)
    }
    @Test fun `disconnected saved and unreadable pairing remain memory only with all active flags false`() {
        for(unreadable in listOf(false,true)) {
            var reads=0;var writes=0;var identities=0
            val storage=RecordingPartCursors({unpairedMultiscreenNamespace(savedPair={!unreadable},unreadablePair={unreadable},identity={identities++;namespace()})},{reads++;null},{_,_->writes++})
            storage.write(owner,snapshot,cursor());assertEquals(cursor(),storage.read(owner,snapshot));assertEquals(0,identities);assertEquals(0,reads);assertEquals(0,writes)
        }
    }
    @Test fun `unreadable identity remains memory only and cannot become a persistent key`() {
        var reads=0;var writes=0
        val storage=RecordingPartCursors({unpairedMultiscreenNamespace(savedPair={false},identity={throw IllegalStateException("Synthetic")})},{reads++;null},{_,_->writes++})
        storage.write(owner,snapshot,cursor());assertEquals(cursor(),storage.read(owner,snapshot));assertEquals(0,reads);assertEquals(0,writes)
    }
    @Test fun `typed identity persists only required part fields with no URLs credentials or global time`() {
        val raw=mutableMapOf<String,String>();val storage=RecordingPartCursors({namespace()},{raw[it]},{key,value->raw[key]=value})
        storage.write(owner,snapshot,cursor());val value=raw.values.single()
        assertTrue(value.contains("localPositionMs"));assertTrue(value.contains("closedSignature"));assertFalse(value.contains("url",true));assertFalse(value.contains("account"));assertFalse(value.contains("token"));assertFalse(value.contains("duration"))
        val fresh=RecordingPartCursors({namespace()},{raw[it]},{_,_->fail("Read must not rewrite stored cursor")})
        assertEquals(cursor(),fresh.read(owner,snapshot))
    }
    @Test fun `server account and profile isolate persistent cursor namespaces`() {
        val current=namespace();assertNotEquals(current,namespace(server="https://other.invalid/proxy"));assertNotEquals(current,namespace(account="other"));assertNotEquals(current,namespace(profile="other"))
        assertFalse(current.contains("account"));assertFalse(current.contains(owner.profileId))
    }
    @Test fun `legacy unnamespaced or foreign raw progress is preserved and never automatically adopted`() {
        val legacy="legacy-recording-position";val foreign="foreign-position"
        val raw=mutableMapOf("queue" to legacy,"${namespace(account="other")}.queue" to foreign)
        val storage=RecordingPartCursors({namespace()},{raw[it]},{key,value->raw[key]=value})
        assertNull(storage.read(owner,snapshot));storage.write(owner,snapshot,cursor())
        assertEquals(legacy,raw["queue"]);assertEquals(foreign,raw["${namespace(account="other")}.queue"]);assertEquals(3,raw.size)
    }
    @Test fun `A B A retires old reads and writes and cannot revive memory cursor`() {
        var reads=0;var writes=0;val storage=RecordingPartCursors({null},{reads++;null},{_,_->writes++})
        storage.write(owner,snapshot,cursor());ProfileDataContext.invalidate();ProfileDataContext.invalidate()
        try{storage.read(owner,snapshot);fail("Old read accepted")}catch(_:CancellationException){}
        try{storage.write(owner,snapshot,cursor());fail("Old write accepted")}catch(_:CancellationException){}
        assertNull(storage.read(PlaybackSessionContext.capture(),snapshot));assertEquals(0,reads);assertEquals(0,writes)
    }
    @Test fun `different closed snapshot or absent original part invalidates stored local resume`() {
        val raw=mutableMapOf<String,String>();val storage=RecordingPartCursors({namespace()},{raw[it]},{key,value->raw[key]=value})
        storage.write(owner,snapshot,cursor())
        val changed=RecordingPartSnapshot.capture(queueRecording(listOf(queuePart(2),queuePart(5).copy(bytes=9999))))!!
        assertNull(storage.read(owner,changed));assertNull(storage.read(owner,RecordingPartSnapshot.capture(queueRecording(listOf(queuePart(2))))!!));assertEquals(cursor(),storage.read(owner,snapshot))
    }
    @Test fun `malformed future and invalid cursor records are not adopted or deleted`() {
        val values=listOf("{",Json.encodeToString(cursor().copy(partIndex=4)),Json.encodeToString(cursor().copy(recordingId="foreign")),Json.encodeToString(cursor().copy(localPositionMs=-1)),Json.encodeToString(cursor().copy(closedSignature="invalid")))
        for(value in values) {
            var writes=0;val raw=mutableMapOf("${namespace()}.queue" to value)
            val storage=RecordingPartCursors({namespace()},{raw[it]},{_,_->writes++})
            assertNull(storage.read(owner,snapshot));assertEquals(0,writes);assertEquals(value,raw.values.single())
        }
    }
    @Test fun `ordinary disk failure preserves memory cursor without a player failure`() {
        val storage=RecordingPartCursors({namespace()},{throw java.io.IOException("Synthetic")},{_,_->throw java.io.IOException("Synthetic")})
        assertNull(storage.read(owner,snapshot));storage.write(owner,snapshot,cursor());assertEquals(cursor(),storage.read(owner,snapshot))
    }
    @Test fun `cancellation of actual local storage adapter is preserved`() {
        val storage=RecordingPartCursors({namespace()},{throw CancellationException("Synthetic")},{_,_->throw CancellationException("Synthetic")})
        try{storage.read(owner,snapshot);fail("Canceled read accepted")}catch(_:CancellationException){}
        try{storage.write(owner,snapshot,cursor());fail("Canceled write accepted")}catch(_:CancellationException){}
    }
    @Test fun `new closed sibling preserves stored local position for unchanged original closed part`() {
        val raw=mutableMapOf<String,String>();val storage=RecordingPartCursors({namespace()},{raw[it]},{key,value->raw[key]=value})
        storage.write(owner,snapshot,cursor())
        val updated=RecordingPartSnapshot.capture(queueRecording(listOf(queuePart(2),queuePart(5),queuePart(9))))!!
        assertEquals(cursor(),storage.read(owner,updated));assertEquals(cursor(),storage.read(owner,snapshot))
    }

}
