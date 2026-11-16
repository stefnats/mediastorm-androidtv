package com.strmr.tv.api

import org.junit.Assert.*
import org.junit.Test

class CastOwnedRequestTest {
    @Test fun `HLS preparation keeps original profile and selected tracks despite other metadata`() {
        val query=castHlsQuery("/synthetic/file.mkv",mapOf("profileId" to "synthetic-other","title" to "Synthetic"),"synthetic-original","Original",true,3,7)
        assertEquals("synthetic-original",query["profileId"]);assertEquals("3",query["audioTrack"]);assertEquals("7",query["subtitleTrack"]);assertEquals("0",query["start"]);assertEquals("true",query["forceAAC"]);assertEquals("Synthetic",query["title"])
    }
    @Test fun `DLNA preparation retains captured profile without inventing track selections`() {
        val query=castHlsQuery("/synthetic/file.mkv",emptyMap(),"synthetic-original",null,false,-1,null)
        assertEquals("synthetic-original",query["profileId"]);assertFalse(query.containsKey("cast"));assertFalse(query.containsKey("audioTrack"));assertFalse(query.containsKey("subtitleTrack"))
    }
}
