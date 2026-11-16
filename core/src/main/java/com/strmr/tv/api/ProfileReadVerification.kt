package com.strmr.tv.api

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** In-memory provenance of one authenticated profile reply, never serialized or a content grant. */
class ProfileReadVerification internal constructor(private val profileId:String,private val context:ProfileVerificationGeneration.Stamp) {
    fun isCurrent(id:String):Boolean {
        if(id!=profileId)return false
        return try {context.check();true} catch(_:CancellationException) {false}
    }
}

/** Only the authenticated profile endpoint may call this with its original authentication scope. */
internal fun authenticatedProfiles(raw:JsonArray,context:AuthenticatedProfileRead):List<Profile> {
    context.check()
    return raw.map {element->
        val fields=element as? JsonObject ?: throw ApiError.NotReady("Profiles could not be read.")
        val profile=ApiJson.decodeFromJsonElement<Profile>(fields)
        val adult=(fields["isKidsProfile"] as? JsonPrimitive)?.let {!it.isString && it.booleanOrNull==false}==true
        profile.copy(offlineVerification=if(adult)ProfileReadVerification(profile.id,context.proof)else null)
    }
}

/** Logical server/account provenance is distinct from a transient HTTP route epoch. */
internal class ProfileVerificationGeneration {
    private val epoch=java.util.concurrent.atomic.AtomicLong()
    fun invalidate() {epoch.incrementAndGet()}
    fun capture()=Stamp(this,epoch.get())
    internal class Stamp(private val owner:ProfileVerificationGeneration,private val epoch:Long) {
        fun check() {if(owner.epoch.get()!=epoch)throw CancellationException("Profile authentication changed")}
    }
}
internal class AuthenticatedProfileRead(private val request:RequestContext.Scope,val proof:ProfileVerificationGeneration.Stamp) {
    fun check() {request.check();proof.check()}
}
