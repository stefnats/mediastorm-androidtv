package com.strmr.mobile.downloads

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.DetailsTarget
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OwnedProgressTest {
    private val owner=DownloadOwner.Known("configured:"+"a".repeat(64),"synthetic-account-a","synthetic-profile-a")
    private fun item(owner:DownloadOwner?=this.owner)=DownloadItem.create(DetailsTarget("tmdb:movie:1","Synthetic",true),null,"/synthetic/file.mkv",null,1).copy(owner=owner)
    @Test fun `progress for equal title IDs remains separate across account and profile owners`() {
        var raw:String?=null;val store=OwnedProgressStore({raw},{raw=it});val a=item();val b=item(owner.copy(profileId="synthetic-b"));val ca=OfflineContext(owner,1);val cb=OfflineContext(b.owner as DownloadOwner.Known,2)
        store.position(a,ca,42.0);store.pending(a,ca,buildJsonObject {put("itemId","tmdb:movie:1");put("position",42)})
        store.position(b,cb,80.0)
        assertEquals(42.0,store.read(a,ca)!!.position!!,0.0);assertNull(store.read(a,cb));assertEquals(80.0,store.read(b,cb)!!.position!!,0.0)
        assertTrue(runCatching {store.position(a,cb,100.0)}.isFailure)
        store.forget(b,cb);assertEquals(42.0,store.read(a,ca)!!.position!!,0.0);assertNotNull(store.entries().single().pending)
    }
    @Test fun `new store never reads writes adopts or clears legacy positions and pending keys`() {
        val prefs=mutableMapOf("positions" to "{\"old-unowned\":42}","pending" to "{\"old\":{\"itemId\":\"synthetic\"}}")
        val old=prefs.toMap();val store=OwnedProgressStore({prefs["owned-v2"]},{prefs["owned-v2"]=it})
        assertTrue(store.entries().isEmpty());val a=item();store.position(a,OfflineContext(owner,1),42.0)
        assertEquals(old["positions"],prefs["positions"]);assertEquals(old["pending"],prefs["pending"])
        assertEquals(1,store.entries().size)
    }
    @Test fun `in-flight delivery cannot clear a newer saved report`() {
        var raw:String?=null;val store=OwnedProgressStore({raw},{raw=it});val a=item();val context=OfflineContext(owner,1)
        store.pending(a,context,buildJsonObject {put("position",1)});val held=store.entries().single()
        store.pending(a,context,buildJsonObject {put("position",2)});store.delivered(held)
        assertEquals(2,store.entries().single().pending!!["position"]!!.jsonPrimitive.int)
    }
    @Test fun `corrupt v2 progress cannot be overwritten as empty or transferred to current profile`() {
        var raw="{broken";val store=OwnedProgressStore({raw},{raw=it});val a=item()
        assertTrue(runCatching {store.entries()}.isFailure);assertTrue(runCatching {store.position(a,OfflineContext(owner,1),42.0)}.isFailure);assertEquals("{broken",raw)
    }
}
