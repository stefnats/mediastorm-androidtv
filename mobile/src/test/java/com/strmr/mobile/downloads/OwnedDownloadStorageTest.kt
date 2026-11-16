package com.strmr.mobile.downloads

import com.strmr.tv.api.ApiJson
import com.strmr.tv.api.DetailsTarget
import java.io.File
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OwnedDownloadStorageTest {
    @get:Rule val temporary=TemporaryFolder()
    private val owner=DownloadOwner.Known("configured:"+"a".repeat(64),"synthetic-account-a","synthetic-profile-a")
    private val context=OfflineContext(owner,1)
    private fun item(owner:DownloadOwner?=this.owner)=DownloadItem.create(DetailsTarget("tmdb:movie:1","Synthetic",true),null,"/synthetic/file.mkv",null,3).copy(owner=owner)
    private fun raw(item:DownloadItem)=ApiJson.encodeToJsonElement(item) as JsonObject
    private fun repository(rows:List<JsonElement>):Pair<File,DownloadRepository> {
        val file=temporary.newFile();file.writeText(JsonArray(rows).toString());return file to DownloadRepository(file)
    }
    @Test fun `known owner roundtrips but legacy and unknown owner never upgrade`() {
        val known=item();val legacy=item(null)
        val future=JsonObject(raw(item())+("owner" to buildJsonObject {put("version",2);put("future","retained")}))
        val(file,repo)=repository(listOf(raw(known),raw(legacy),future))
        val rows=repo.load();assertEquals(owner,rows[0].owner);assertNull(rows[1].owner);assertTrue(rows[2].owner is DownloadOwner.Unrecognized)
        assertTrue(repo.save(1,rows));assertEquals(future["owner"],(ApiJson.parseToJsonElement(file.readText()) as JsonArray)[2].jsonObject["owner"])
    }
    @Test fun `malformed known owner cannot grant and remains verbatim`() {
        for(replacement in listOf(JsonPrimitive("1"),JsonPrimitive(2),JsonNull)) {
            val bad=buildJsonObject {put("version",replacement);put("serverKey",owner.serverKey);put("accountId",owner.accountId);put("profileId",owner.profileId)}
            val row=JsonObject(raw(item())+("owner" to bad));val(file,repo)=repository(listOf(row));val parsed=repo.load()
            assertTrue(parsed.single().owner is DownloadOwner.Unrecognized);repo.save(1,parsed)
            assertEquals(row,(ApiJson.parseToJsonElement(file.readText()) as JsonArray).single())
        }
    }
    @Test fun `foreign legacy opaque rows and files survive owned update and deletion`() {
        val own=item();val foreign=item(owner.copy(profileId="synthetic-b"));val legacy=item(null)
        val future=JsonObject(raw(item())+mapOf("status" to JsonPrimitive("FUTURE_STATUS"),"future" to JsonPrimitive("preserved")))
        val foreignRaw=JsonObject(raw(foreign)+("future" to buildJsonObject {put("privateMetadata","preserved")}))
        val(file,repo)=repository(listOf(raw(own),foreignRaw,raw(legacy),future));val rows=repo.load()
        val dir=temporary.newFolder();listOf(own,foreign,legacy).forEach {File(dir,it.fileName).writeText("abc")};File(dir,"old-unindexed.bin").writeText("legacy")
        val guard=object:DownloadFileGuard {override fun<T> use(context:OfflineContext,item:DownloadItem,action:()->T):T {check(item.owner==context.owner);return action()}}
        val files=OwnedDownloadFiles(dir,guard);files.remove(own,context)
        repo.save(2,rows.filterNot {it.id==own.id})
        val saved=ApiJson.parseToJsonElement(file.readText()) as JsonArray
        assertTrue(foreignRaw in saved);assertTrue(raw(legacy) in saved);assertTrue(future in saved)
        assertEquals("abc",File(dir,foreign.fileName).readText());assertEquals("abc",File(dir,legacy.fileName).readText());assertEquals("legacy",File(dir,"old-unindexed.bin").readText())
    }
    @Test fun `all duplicate physical identities including opaque rows are quarantined without changing bytes`() {
        for(opaque in listOf(false,true)) {
            val a=item();val b=JsonObject(raw(a.copy(owner=owner.copy(profileId="synthetic-b")))+if(opaque)mapOf("status" to JsonPrimitive("FUTURE"))else emptyMap())
            val(file,repo)=repository(listOf(raw(a),b));val dir=temporary.newFolder();val media=File(dir,a.fileName).apply {writeText("owned-by-neither-context")}
            assertTrue(repo.load().isEmpty());assertTrue(repo.save(1,emptyList()));assertEquals(JsonArray(listOf(raw(a),b)),ApiJson.parseToJsonElement(file.readText()));assertEquals("owned-by-neither-context",media.readText())
        }
    }
    @Test fun `corrupt top-level read cannot be followed by empty overwrite`() {
        val file=temporary.newFile().apply {writeText("{broken")};val repo=DownloadRepository(file)
        assertFalse(repo.save(0,emptyList()));assertTrue(runCatching {repo.load()}.isFailure);assertFalse(repo.save(1,emptyList()));assertEquals("{broken",file.readText())
    }
    @Test fun `out of order persistence never rolls a newer whole ledger back`() {
        val a=item();val(file,repo)=repository(listOf(raw(a)));repo.load();assertTrue(repo.save(2,listOf(a.copy(status=DownloadItem.Status.PAUSED))));assertFalse(repo.save(1,listOf(a)))
        assertEquals(DownloadItem.Status.PAUSED,DownloadRepository(file).load().single().status)
    }
    @Test fun `unsafe file identity cannot escape download directory`() {
        val a=item();assertTrue(OwnedDownloadFiles.safe(a))
        assertFalse(OwnedDownloadFiles.safe(a.copy(fileName="../foreign.mkv")));assertFalse(OwnedDownloadFiles.safe(a.copy(id="../foreign")));assertFalse(OwnedDownloadFiles.safe(a.copy(fileName=a.id+".mkv/other")))
    }
    @Test fun `reserved temporary and poster suffix cannot delete or overwrite stored sentinels`() {
        val a=item();val dir=temporary.newFolder();val guard=object:DownloadFileGuard {override fun<T> use(context:OfflineContext,item:DownloadItem,action:()->T)=action()};val files=OwnedDownloadFiles(dir,guard)
        for(suffix in listOf("part","jpg","json")) {
            val target=File(dir,a.id+"."+suffix).apply {writeText("preserved")};val malformed=a.copy(fileName=target.name)
            assertFalse(OwnedDownloadFiles.safe(malformed));assertTrue(runCatching {files.finish(malformed,context)}.isFailure);assertTrue(runCatching {files.remove(malformed,context)}.isFailure);assertEquals("preserved",target.readText())
        }
    }
    @Test fun `configured key excludes credentials and request routing data while account profile remain separate`() {
        val a=DownloadServerKey.configured("https://synthetic-user:synthetic-password@example.invalid/server/?secret=synthetic#fragment".toHttpUrl())
        val b=DownloadServerKey.configured("https://example.invalid/server/".toHttpUrl())
        assertEquals(a,b);assertFalse(a!!.contains("synthetic"));assertNotEquals(b,DownloadServerKey.configured("https://example.invalid/other/".toHttpUrl()))
        assertNotEquals(owner,owner.copy(accountId="synthetic-b"));assertNotEquals(owner,owner.copy(profileId="synthetic-b"))
        assertEquals(DownloadServerKey.paired("a".repeat(64)),DownloadServerKey.paired("A".repeat(64)));assertNull(DownloadServerKey.paired("arbitrary-server-label"))
    }
    @Test fun `signed out unknown child PIN challenged or mismatched current profile are unavailable`() {
        assertNotNull(offlineContext(owner,1,true,true,true,true))
        assertNull(offlineContext(owner,1,false,true,true,true));assertNull(offlineContext(owner,1,true,false,true,true));assertNull(offlineContext(owner,1,true,true,false,true));assertNull(offlineContext(owner,1,true,true,true,false));assertNull(offlineContext(null,1,true,true,true,true))
        assertNull(offlineContext(owner.copy(accountId=""),1,true,true,true,true))
    }
}
