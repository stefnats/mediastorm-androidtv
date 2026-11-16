package com.strmr.mobile.downloads

import com.strmr.tv.api.ApiJson
import java.security.MessageDigest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import okhttp3.HttpUrl

/** Unknown future/legacy ownership is retained verbatim, with no access permission. */
@Serializable(with=DownloadOwnerSerializer::class)
sealed interface DownloadOwner {
    data class Known(val serverKey:String,val accountId:String,val profileId:String):DownloadOwner {
        fun valid()=Regex("(paired|configured):[0-9a-f]{64}").matches(serverKey) && listOf(accountId,profileId).all {it.length in 1..1024 && it.none(Char::isISOControl)}
    }
    class Unrecognized internal constructor(internal val raw:JsonElement):DownloadOwner {
        override fun toString()="Unrecognized download owner"
    }
}

object DownloadOwnerSerializer:KSerializer<DownloadOwner> {
    override val descriptor=buildClassSerialDescriptor("DownloadOwner")
    override fun deserialize(decoder:Decoder):DownloadOwner {
        val raw=(decoder as JsonDecoder).decodeJsonElement();val obj=raw as? JsonObject
        fun text(key:String)=(obj?.get(key) as? JsonPrimitive)?.takeIf {it.isString}?.contentOrNull
        val version=(obj?.get("version") as? JsonPrimitive)?.takeUnless {it.isString}?.intOrNull
        val known=if(version==1 && obj!=null && obj.keys==setOf("version","serverKey","accountId","profileId")) {
            val server=text("serverKey");val account=text("accountId");val profile=text("profileId")
            if(server!=null && account!=null && profile!=null)DownloadOwner.Known(server,account,profile).takeIf {it.valid()}else null
        } else null
        return known ?: DownloadOwner.Unrecognized(raw)
    }
    override fun serialize(encoder:Encoder,value:DownloadOwner) {
        val raw=when(value) {
            is DownloadOwner.Unrecognized->value.raw
            is DownloadOwner.Known->buildJsonObject {put("version",1);put("serverKey",value.serverKey);put("accountId",value.accountId);put("profileId",value.profileId)}
        }
        (encoder as JsonEncoder).encodeJsonElement(raw)
    }
}

/** Logical identity survives ordinary home/away routing; no credentials or token form a key. */
internal object DownloadServerKey {
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun paired(verifiedId:String?):String?=verifiedId?.lowercase()?.takeIf {Regex("[0-9a-f]{64}").matches(it)}?.let {"paired:"+hash("paired-v1:$it")}
    fun configured(url:HttpUrl?):String?=url?.newBuilder()?.username("")?.password("")?.query(null)?.fragment(null)?.build()?.let {"configured:"+hash("configured-v1:$it")}
}
