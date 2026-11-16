package com.strmr.tv.live

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiJson
import com.strmr.tv.library.ProfileDataContext
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StationFavoritesContractTest {
    private val a="station:"+"a".repeat(24)
    private val b="station:"+"b".repeat(24)
    private fun obj(value:String)=ApiJson.parseToJsonElement(value).jsonObject
    @Test fun `only actual boolean capability and strict server station identities enable writes`() {
        for(marker in listOf("false","null","\"true\"","1")) {
            val settings=liveChannelPreferences(obj("""{"stationFavoritesSupported":$marker,"liveTV":{"favoriteChannels":["one"],"hiddenChannels":["two"],"favoriteStationIds":["$a"]}}"""))
            assertFalse(settings.stationFavoritesSupported);assertTrue(settings.favoriteStationIds.isEmpty());assertEquals(setOf("one"),settings.favorites);assertEquals(setOf("two"),settings.hidden)
        }
        val settings=liveChannelPreferences(obj("""{"stationFavoritesSupported":true,"liveTV":{"favoriteStationIds":["$a","$a"]}}"""))
        assertTrue(settings.stationFavoritesSupported);assertEquals(setOf(a),settings.favoriteStationIds)
        for(id in listOf(a.uppercase()," $a",a+"0","RTL", "station:"+"g".repeat(24)))assertFalse(StationFavoriteIds.valid(id))
    }
    @Test fun `malformed optional station section is unreadable while valid empty object inherits empty`() {
        for(section in listOf("null","[]","1","\"text\"")) {
            val settings=liveChannelPreferences(obj("""{"stationFavoritesSupported":true,"liveTV":$section}"""));assertFalse(settings.stationFavoritesReadable)
        }
        assertFalse(liveChannelPreferences(obj("""{"stationFavoritesSupported":true}""")).stationFavoritesReadable)
        assertTrue(liveChannelPreferences(obj("""{"stationFavoritesSupported":true,"liveTV":{}}""")).stationFavoritesReadable)
        for(ids in listOf("null","{}","[true]","[1]","[\"bad\"]")) {
            val settings=liveChannelPreferences(obj("""{"stationFavoritesSupported":true,"liveTV":{"favoriteChannels":["one"],"favoriteStationIds":$ids}}"""));assertFalse(settings.stationFavoritesReadable);assertEquals(setOf("one"),settings.favorites)
        }
    }
    @Test fun `strict authoritative snapshots accept ten thousand ids without silent truncation`() {
        val ids=(0 until 10_000).map {"station:"+it.toString(16).padStart(24,'0')}
        val valid=buildJsonObject {putJsonArray("favoriteStationIds"){ids.forEach {add(it)}}}
        assertEquals(10_000,stationFavoriteSnapshot(valid).favoriteStationIds.size)
        // Build the oversized array explicitly; no bounds are inferred from a partial decode.
        val oversized=buildJsonObject {putJsonArray("favoriteStationIds"){ids.forEach {add(it)};add("station:"+"f".repeat(24))}}
        for(value in listOf(obj("{}"),obj("""{"favoriteStationIds":null}"""),obj("""{"favoriteStationIds":["bad"]}"""),oversized)) {
            try {stationFavoriteSnapshot(value);fail("Malformed snapshot accepted")}catch(_:com.strmr.tv.api.ApiError.NotReady){}
        }
    }
    @Test fun `dedicated writes contain only exact station identity and explicit desired boolean`() {
        for(desired in listOf(true,false)) {
            val body=stationFavoriteBody(a,desired);assertEquals(setOf("stationId","favorite"),body.keys);assertEquals(a,body["stationId"]!!.jsonPrimitive.content);assertEquals(desired,body["favorite"]!!.jsonPrimitive.boolean)
        }
    }
    @Test fun `legacy absent supported empty and station favorites channel bodies remain distinct`() {
        fun body(ids:List<String>?,hidden:Boolean?=true)=liveChannelsBody("adult",listOf("stream"),true,0,1,favoriteStationIds=ids,includeHidden=hidden)
        assertFalse(body(null).containsKey("favoriteStationIds"));assertFalse(body(null).containsKey("includeHidden"))
        assertEquals(JsonArray(emptyList()),body(emptyList())["favoriteStationIds"]);assertFalse(body(emptyList()).containsKey("includeHidden"))
        val query=body(listOf(b,a,a));assertEquals(listOf(a,b),query["favoriteStationIds"]!!.jsonArray.map {it.jsonPrimitive.content});assertEquals(true,query["includeHidden"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("stream"),query["favoriteIds"]!!.jsonArray.map {it.jsonPrimitive.content});assertEquals(false,body(listOf(a),false)["includeHidden"]!!.jsonPrimitive.boolean)
    }
    @Test fun `stale dedicated settings catalog and epg calls reject before constructing replacement profile requests`() = runTest {
        val old=ProfileDataContext.current;ProfileDataContext.invalidate()
        suspend fun rejected(call:suspend ()->Unit) {try {call();fail("Old request accepted")}catch(_:CancellationException){}}
        rejected {ApiClient.setStationFavorite(a,true,old)};rejected {ApiClient.liveChannelPreferences(old)}
        rejected {ApiClient.liveChannels(emptyList(),false,0,1,expectedContext=old,favoriteStationIds=listOf(a))};rejected {ApiClient.epgEnabled(old)}
    }
    @Test fun `actual native request exports preserve dedicated transport and channel opt in contract`() {
        val path=System.getProperty("stationFavoritesContractsDir") ?: return
        val dir=File(path).apply {mkdirs()};val rtl="station:8fb157ee01b29cf5af3b8d7f"
        File(dir,"native-station-add.json").writeText(stationFavoriteBody(rtl,true).toString())
        File(dir,"native-station-remove.json").writeText(stationFavoriteBody(rtl,false).toString())
        File(dir,"native-channels-favorites.json").writeText(liveChannelsBody("adult",emptyList(),true,0,1,groupByStation=true,favoriteStationIds=listOf(rtl),includeHidden=true).toString())
        File(dir,"native-channels-supported-all.json").writeText(liveChannelsBody("adult",emptyList(),false,0,1,favoriteStationIds=emptyList()).toString())
        File(dir,"native-channels-legacy.json").writeText(liveChannelsBody("adult",emptyList(),true,0,1).toString())
    }
    @Test fun `actual Go HTTP responses decode with marker station ids and complete eligible variant count`() {
        fun resource(name:String)=requireNotNull(javaClass.getResourceAsStream("/station-favorites-go/$name")).use {it.readBytes()}
        val hashes=obj(resource("sha256.json").decodeToString())
        for((name,digest) in hashes)assertEquals(digest.jsonPrimitive.content,MessageDigest.getInstance("SHA-256").digest(resource(name)).joinToString(""){"%02x".format(it.toInt() and 255)})
        val manifest=obj(resource("manifest.json").decodeToString());assertEquals(1,manifest["version"]!!.jsonPrimitive.int)
        val rtl="station:8fb157ee01b29cf5af3b8d7f"
        val settings=liveChannelPreferences(obj(resource("settings-supported.json").decodeToString()));assertTrue(settings.stationFavoritesSupported);assertTrue(settings.stationFavoritesReadable);assertEquals(setOf(rtl),settings.favoriteStationIds)
        assertEquals(setOf(rtl),stationFavoriteSnapshot(obj(resource("favorite-snapshot.json").decodeToString())).favoriteStationIds)
        assertTrue(stationFavoriteSnapshot(obj(resource("empty-snapshot.json").decodeToString())).favoriteStationIds.isEmpty())
        val page=ApiJson.decodeFromString<LiveChannelsPage>(resource("channels-station-favorites.json").decodeToString());assertEquals(2,page.total);assertTrue(page.hasMore);assertEquals(1,page.channels.size);assertEquals(rtl,page.channels.single().stationId);assertEquals(2,page.channels.single().stationVariants)
        val request=obj(resource("channels-request.json").decodeToString());assertEquals(setOf(rtl),StationFavoriteIds.parse(request["favoriteStationIds"]));assertEquals(true,request["includeHidden"]!!.jsonPrimitive.boolean)
    }
}
