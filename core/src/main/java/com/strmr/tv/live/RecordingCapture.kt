package com.strmr.tv.live

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

/** Optional capture ledger; unsupported or malformed data never removes its recording. */
@Serializable(with=RecordingCaptureSerializer::class)
data class RecordingCapture(
    val version:Int=0, val phase:String?=null, val incomplete:Boolean?=null, val sourceChanges:Int?=null,
    val parts:List<RecordingPart> = emptyList(), val gaps:List<RecordingGap> = emptyList(),
)

data class RecordingPart(val index:Int,val startedAt:String,val endedAt:String?=null,val bytes:Long?=null,val endReason:String?=null,val streamPath:String?=null)
data class RecordingGap(val startedAt:String,val endedAt:String?=null,val reason:String)

private fun JsonObject.text(key:String,max:Int=512)= (get(key) as? JsonPrimitive)?.takeIf {it.isString}?.contentOrNull?.takeIf {it.length in 1..max && it.none(Char::isISOControl)}
private fun JsonObject.integer(key:String)= (get(key) as? JsonPrimitive)?.takeUnless {it.isString}?.intOrNull
private fun JsonObject.bytes()= (get("bytes") as? JsonPrimitive)?.takeUnless {it.isString}?.longOrNull?.takeIf {it>=0}
private fun JsonObject.boolean(key:String)= (get(key) as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull

object RecordingCaptureSerializer:KSerializer<RecordingCapture> {
    override val descriptor=buildClassSerialDescriptor("RecordingCapture")
    override fun deserialize(decoder:Decoder)=parse((decoder as JsonDecoder).decodeJsonElement())
    fun parse(value:JsonElement?):RecordingCapture {
        val unknown=RecordingCapture();val obj=value as? JsonObject ?: return unknown
        if(obj.integer("version")!=1)return unknown
        val phase=obj.text("phase")?.takeIf {it in setOf("capturing","waiting_capacity","finished","interrupted")} ?: return unknown
        val incomplete=obj.boolean("incomplete") ?: return unknown
        val changes=obj.integer("sourceChanges")?.takeIf {it>=0} ?: return unknown
        val rawParts=obj["parts"] as? JsonArray ?: return unknown
        val rawGaps=obj["gaps"] as? JsonArray ?: return unknown
        if(rawParts.size>256 || rawGaps.size>256)return unknown
        val parts=rawParts.map { raw ->
            val part=raw as? JsonObject ?: return unknown
            val index=part.integer("index")?.takeIf {it in 1..256} ?: return unknown
            val started=part.text("startedAt",64)?.takeIf {RecordingRules.instant(it)!=null} ?: return unknown
            val ended=part.text("endedAt",64)
            if(part["endedAt"]!=null && part["endedAt"]!=JsonNull && (ended==null || RecordingRules.instant(ended)==null))return unknown
            if(ended!=null && RecordingRules.instant(ended)!!.isBefore(RecordingRules.instant(started)))return unknown
            val size=part.bytes()
            if(part["bytes"]!=null && part["bytes"]!=JsonNull && size==null)return unknown
            RecordingPart(index,started,ended,size,part.text("endReason",64),part.text("streamPath"))
        }
        if(parts.map {it.index}.distinct().size!=parts.size)return unknown
        val gaps=rawGaps.map {raw ->
            val gap=raw as? JsonObject ?: return unknown
            val start=gap.text("startedAt",64)?.takeIf {RecordingRules.instant(it)!=null} ?: return unknown
            val reason=gap.text("reason",64)?.takeIf {it in setOf("source_retry","capacity","access_changed")} ?: return unknown
            val end=gap.text("endedAt",64)
            if(gap["endedAt"]!=null && gap["endedAt"]!=JsonNull && (end==null || RecordingRules.instant(end)==null))return unknown
            if(end!=null && RecordingRules.instant(end)!!.isBefore(RecordingRules.instant(start)))return unknown
            RecordingGap(start,end,reason)
        }
        return RecordingCapture(1,phase,incomplete,changes,parts.sortedBy {it.index},gaps)
    }
    override fun serialize(encoder:Encoder,value:RecordingCapture) {
        (encoder as JsonEncoder).encodeJsonElement(buildJsonObject {
            put("version",value.version);value.phase?.let {put("phase",it)};value.incomplete?.let {put("incomplete",it)};value.sourceChanges?.let {put("sourceChanges",it)}
            putJsonArray("parts") {value.parts.forEach {part -> add(buildJsonObject {put("index",part.index);put("startedAt",part.startedAt);part.endedAt?.let {put("endedAt",it)};part.bytes?.let {put("bytes",it)};part.endReason?.let {put("endReason",it)};part.streamPath?.let {put("streamPath",it)}})}}
            putJsonArray("gaps") {value.gaps.forEach {gap -> add(buildJsonObject {put("startedAt",gap.startedAt);gap.endedAt?.let {put("endedAt",it)};put("reason",gap.reason)})}}
        })
    }
}

/** A part's byte count and capture interval never establish a continuous media timeline. */
object RecordingCaptureRules {
    private val reasons=setOf("schedule","source_ended","capture_failed","cancelled","shutdown","restart")
    fun known(r:Recording)=r.capture?.takeIf {it.version==1}
    fun incomplete(r:Recording)=known(r)?.let {it.incomplete!=false || it.parts.size>1 || it.gaps.isNotEmpty() || it.phase=="interrupted"} ?: false
    fun status(r:Recording):String=when {
        known(r)?.phase=="waiting_capacity" && RecordingRules.canCancel(r)->"Waiting for recording capacity"
        incomplete(r) && r.status in setOf("completed","failed")->"Incomplete recording"
        else->RecordingRules.statusLabel(r.status)
    }
    fun summary(r:Recording):String? {
        val c=r.capture ?: return null
        if(c.version!=1)return "Capture details are unavailable."
        return when {
            c.phase=="waiting_capacity"->"Waiting for recording capacity. Any captured parts remain separate."
            incomplete(r)->"This recording is incomplete. Available parts can be selected individually from their start."
            c.phase=="capturing"->"Recording in progress. The capture window is not the playable duration."
            else->"Capture finished. Saved parts can be selected individually."
        }
    }
    fun closed(part:RecordingPart)=part.endedAt!=null && RecordingRules.instant(part.startedAt)!=null &&
        RecordingRules.instant(part.endedAt)?.let { !it.isBefore(RecordingRules.instant(part.startedAt)) }==true && part.endReason in reasons
    private fun ownedPart(r:Recording,part:RecordingPart)=part.index in 1..256 && r.id.length in 1..128 && Regex("[A-Za-z0-9_-]+").matches(r.id) && part.streamPath=="recording:${r.id}/parts/${part.index}"
    fun playable(r:Recording,part:RecordingPart):Boolean = known(r)?.parts?.contains(part)==true && closed(part) && (part.bytes ?: 0)>0 && ownedPart(r,part)
    /** The legacy/default file is the lowest nonempty owned part, including after an empty attempt. */
    fun firstClosed(r:Recording)=known(r)?.parts?.filter {(it.bytes ?: 0)>0 && ownedPart(r,it)}?.minByOrNull {it.index}?.takeIf {playable(r,it)}
    /** A chosen closed snapshot never skips an unreadable positive-byte closed attempt. */
    fun queueParts(r:Recording):List<RecordingPart>? {
        val closedPositive=known(r)?.parts?.filter {it.endedAt!=null && (it.bytes ?: 0)>0} ?: return null
        if(closedPositive.size !in 1..256 || closedPositive.map {it.index}.distinct().size!=closedPositive.size || closedPositive.any {!playable(r,it)})return null
        return closedPositive.sortedBy {it.index}.toList()
    }
    fun gapLabel(gap:RecordingGap):String {
        val reason=when(gap.reason) {"source_retry"->"Source retry";"capacity"->"Waiting for capacity";else->"Access changed"}
        return "Capture gap (wall clock): ${RecordingRules.dateTime(gap.startedAt)} to ${gap.endedAt?.let(RecordingRules::dateTime) ?: "unconfirmed end"} · $reason"
    }
    fun playLabel(r:Recording)=when {
        queueParts(r)!=null->"Play / resume completed parts"
        firstClosed(r)?.index?.let {it>1}==true->"Play part ${firstClosed(r)!!.index}"
        known(r)?.parts?.size?.let {it>1}==true || firstClosed(r)!=null && (RecordingRules.canCancel(r) || incomplete(r) || known(r)?.phase=="waiting_capacity")->"Play first part"
        RecordingRules.isRecording(r)->"Play In Progress"
        else->"Play Recording"
    }
    private fun activePart(r:Recording?,part:RecordingPart)=part.endedAt==null && (r==null || known(r)?.phase=="capturing" && r.status in setOf("starting","running"))
    fun endLabel(part:RecordingPart,recording:Recording?=null):String {
        if(!closed(part))return if(activePart(recording,part))"Still capturing; play after this part closes" else "Part end is unconfirmed; playback is unavailable."
        return when(part.endReason) {
        "schedule"->"Scheduled capture ended";"source_ended"->"Source ended";"capture_failed"->"Capture interrupted"
        "cancelled"->"Recording stopped";"shutdown"->"Server stopped";"restart"->"Server restarted"
        else->"Part end is unconfirmed; playback is unavailable."
        }
    }
    fun scheduledDuration(r:Recording)=RecordingRules.duration(r.startAt,r.endAt).takeIf {it.isNotEmpty()}?.let {if(r.capture==null)it else "Scheduled: $it"}
    fun dataLabel(r:Recording)=when {
        r.capture==null->"Size"
        known(r)==null->"Saved file data"
        RecordingRules.canCancel(r)->"Captured data so far"
        else->"Captured data"
    }
    fun dataBytes(r:Recording)=if(known(r)!=null)totalBytes(r)else r.outputSizeBytes
    fun dataText(r:Recording)=RecordingRules.size(dataBytes(r))?.let {if(r.capture==null)it else "${dataLabel(r)}: $it"}
    fun partDataLabel(part:RecordingPart,recording:Recording?=null)=if(closed(part))"Captured data" else if(activePart(recording,part))"Captured data so far" else "Last measured data"
    fun partDataSize(part:RecordingPart,recording:Recording?=null)=part.bytes?.takeIf {it>0}?.let(RecordingRules::size) ?: if(part.bytes==0L && closed(part))"0 bytes" else if(activePart(recording,part))"Not measured yet" else "Not measured"
    fun totalBytes(r:Recording):Long? {
        val parts=known(r)?.parts ?: return null
        if(parts.isEmpty() || parts.any {it.bytes==null})return null
        return try {parts.fold(0L) {sum,part->Math.addExact(sum,part.bytes!!)}}catch(_:ArithmeticException){null}
    }
}
