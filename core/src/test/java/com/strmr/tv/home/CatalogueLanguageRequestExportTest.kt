package com.strmr.tv.home

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.ApiJson
import com.strmr.tv.library.ProfileDataContext
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Actual production query/path helpers; GET transport registration is separately source-traced. */
class CatalogueLanguageRequestExportTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun `export actual native catalogue GET helper bytes for server parser verification`() {
        val expected = ProfileDataContext.current
        val profile = ApiClient.inContext(expected) { ApiClient.profileId }
        val path = ApiClient.browsePath(expected)
        assertEquals("/api/users/${ApiClient.segment(profile)}/display-list", path)
        data class Input(val list: BrowseList, val options: BrowseOptions, val limit: Int = 60, val offset: Int = 0)
        val inputs = linkedMapOf(
            "custom-unfiltered.json" to Input(BrowseList.Custom("synthetic-list","Synthetic"),BrowseOptions()),
            "watchlist-languages.json" to Input(BrowseList.Watchlist,BrowseOptions(originalLanguages=setOf("ger","fre","deu"))),
            "permanent-unknown-only.json" to Input(BrowseList.PermanentPrequeue,BrowseOptions(includeUnknownOriginalLanguage=true)),
            "custom-language-or-unknown.json" to Input(BrowseList.Custom("synthetic-list","Synthetic"),BrowseOptions(originalLanguages=setOf("spa"),includeUnknownOriginalLanguage=true)),
            "watchlist-next-page.json" to Input(BrowseList.Watchlist,BrowseOptions(originalLanguages=setOf("fra")),offset=60),
            "watchlist-random.json" to Input(BrowseList.Watchlist,BrowseOptions(originalLanguages=setOf("fra"),includeUnknownOriginalLanguage=true),limit=1,offset=2),
        )
        val bytes = inputs.mapValues { (_, input) ->
            val query = browseQuery(input.list,input.limit,input.offset,emptyMap(),input.options)
            val raw = buildJsonObject {
                put("method","GET");put("path",path)
                put("query",buildJsonObject { query.toSortedMap().forEach { (key,value) -> put(key,value) } })
            }.toString().toByteArray()
            val parsed=ApiJson.parseToJsonElement(String(raw)).jsonObject
            assertEquals("GET",parsed.getValue("method").jsonPrimitive.content)
            assertEquals(input.limit.toString(),parsed.getValue("query").jsonObject.getValue("limit").jsonPrimitive.content)
            raw
        }
        assertFalse(ApiJson.parseToJsonElement(String(bytes.getValue("custom-unfiltered.json"))).jsonObject.getValue("query").jsonObject.containsKey("originalLanguages"))
        assertEquals("deu,fra",ApiJson.parseToJsonElement(String(bytes.getValue("watchlist-languages.json"))).jsonObject.getValue("query").jsonObject.getValue("originalLanguages").jsonPrimitive.content)
        // The assertions always run. The isolated build additionally exports the identical bytes.
        System.getProperty("sourceFilterContractsDir")?.let { directory ->
            val output=File(directory).apply{mkdirs()};bytes.forEach{(name,raw)->File(output,name).writeBytes(raw)}
            File(output,"manifest.json").writeText(buildJsonObject {
                put("version",1);put("profileId",profile);put("requestOrigin","Actual HomeApi.browseQuery and ApiClient.browsePath helpers")
                put("transportLimit","No successful Android ApiClient.send claimed; method/path consumption source-reviewed")
                put("files",buildJsonObject{bytes.keys.forEach{put(it,"CatalogueGetRequest")}})
            }.toString())
            File(output,"sha256.json").writeText(buildJsonObject { bytes.forEach{(name,raw)->put(name,sha(raw))} }.toString())
        }
    }
}
