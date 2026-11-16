package com.strmr.tv.api

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProfileReadVerificationTest {
    private class Fixture {
        var epoch=0L
        val request=RequestContext({epoch},{epoch++})
        val identity=ProfileVerificationGeneration()
        fun context()=AuthenticatedProfileRead(request.captureAuthentication(),identity.capture())
        fun read(text:String)=authenticatedProfiles(ApiJson.parseToJsonElement(text) as JsonArray,context())
        fun route()=request.changeAuthentication(change={true})
        fun authentication() {identity.invalidate();request.changeAuthentication(change={true})}
    }
    private fun profile(value:String)= """[{"id":"synthetic-adult","name":"Adult","isKidsProfile":$value}]"""
    private fun cancelled(action:()->Unit) {try {action();fail("Stale profile reply accepted")}catch(_:CancellationException){}}
    @Test fun `actual Go profile handler bytes mint only explicit adult proof`() {
        val bytes=javaClass.getResourceAsStream("/offline-profiles-go/profiles.json")!!.readBytes()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        assertEquals("8487147eab225af9bf3032e7d7179cad24c0ae84d6f0f813b02f65b5cd047f88",hash)
        val profiles=Fixture().read(bytes.toString(Charsets.UTF_8))
        assertEquals(2,profiles.size)
        assertEquals(1,profiles.count {it.offlineAdultVerified})
        assertTrue(profiles.first {it.isKidsProfile==false}.offlineAdultVerified)
        assertFalse(profiles.first {it.isKidsProfile==true}.offlineAdultVerified)
    }
    @Test fun `missing null string and child flags cannot mint adult proof`() {
        for(value in listOf("null","true","\"false\"","\"true\"")) {
            val decoded=runCatching {Fixture().read(profile(value))}.getOrNull()
            assertFalse(decoded?.any {it.offlineAdultVerified}==true)
        }
        assertFalse(Fixture().read("""[{"id":"synthetic-adult","name":"Adult"}]""").single().offlineAdultVerified)
    }
    @Test fun `cache roundtrip and copying proof to another profile never authorize`() {
        val original=Fixture().read(profile("false")).single()
        assertTrue(original.offlineAdultVerified)
        val cached=ApiJson.decodeFromString<Profile>(ApiJson.encodeToString(original))
        assertFalse(cached.offlineAdultVerified)
        assertFalse(original.copy(id="synthetic-other").offlineAdultVerified)
        assertFalse(Profile("synthetic-adult","Adult",isKidsProfile=false).offlineAdultVerified)
    }
    @Test fun `verified route keeps existing proof but rejects old in-flight raw reply`() {
        val f=Fixture();val original=f.read(profile("false")).single();val pending=f.context()
        f.route()
        assertTrue(original.offlineAdultVerified)
        cancelled {authenticatedProfiles(ApiJson.parseToJsonElement(profile("false")) as JsonArray,pending)}
    }
    @Test fun `session replacement or signout permanently retires earlier proof even on same endpoint`() {
        val f=Fixture();val original=f.read(profile("false")).single();f.authentication()
        assertFalse(original.offlineAdultVerified)
        f.authentication();assertFalse(original.offlineAdultVerified)
        assertTrue(f.read(profile("false")).single().offlineAdultVerified)
    }
    @Test fun `profile epoch cannot revive old work after A B A while adult identity proof stays separate`() {
        val f=Fixture();val proof=f.read(profile("false")).single();val operation=f.request.capture()
        f.request.changeProfile {};f.request.changeProfile {}
        cancelled {operation.check()};assertTrue(proof.offlineAdultVerified)
    }
}
