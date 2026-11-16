package com.strmr.tv.sources

import java.util.Locale
import com.strmr.tv.api.ApiJson
import kotlinx.serialization.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SourceFilterBasis(val label:String) {
    @SerialName("candidate") CANDIDATE("Release claims"),
    @SerialName("probed") PROBED("Existing file metadata"),
}
@Serializable
enum class SourceFilterUnknown {
    @SerialName("include") INCLUDE,
    @SerialName("exclude") EXCLUDE,
}

/** Display criteria are additional to playback rules; selecting them never saves track preferences. */
@Serializable
data class SourceFilters(
    val basis:SourceFilterBasis=SourceFilterBasis.CANDIDATE,
    val unknown:SourceFilterUnknown=SourceFilterUnknown.INCLUDE,
    val audioLanguage:String?=null,
    val subtitleLanguage:String?=null,
    val claimedResolutionAtLeast:String?=null,
    val heightAtLeast:Int?=null,
    val videoCodec:String?=null,
    val dynamicRange:String?=null,
    val audioCodec:String?=null,
    val audioDescription:Boolean?=null,
) {
    val active get()=listOf(audioLanguage,subtitleLanguage,claimedResolutionAtLeast,heightAtLeast,videoCodec,dynamicRange,audioCodec,audioDescription).any {it!=null}
    fun withBasis(value:SourceFilterBasis)=copy(basis=value,
        claimedResolutionAtLeast=claimedResolutionAtLeast.takeIf {value==SourceFilterBasis.CANDIDATE},
        heightAtLeast=heightAtLeast.takeIf {value==SourceFilterBasis.PROBED},
        audioCodec=audioCodec.takeIf {value==SourceFilterBasis.PROBED},audioDescription=audioDescription.takeIf {value==SourceFilterBasis.PROBED})
    fun valid():Boolean {
        if(audioLanguage?.let {SourceFilterLanguages.canonical(it)!=it}==true || subtitleLanguage?.let {SourceFilterLanguages.canonical(it)!=it}==true)return false
        if(claimedResolutionAtLeast?.let {resolution->!Regex("[0-9]{3,4}p").matches(resolution) || resolution.dropLast(1).toIntOrNull()?.let {it !in 144..8640 || "${it}p"!=resolution}!=false}==true || heightAtLeast?.let {it !in 144..8640}==true)return false
        if(listOfNotNull(videoCodec,audioCodec).any {!Regex("[a-z0-9_]{1,32}").matches(it)})return false
        if(dynamicRange!=null && dynamicRange !in dynamicRanges || audioDescription==false)return false
        return when(basis) {
            SourceFilterBasis.CANDIDATE->heightAtLeast==null && audioCodec==null && audioDescription==null
            SourceFilterBasis.PROBED->claimedResolutionAtLeast==null
        }
    }
    companion object {
        val resolutions=listOf("480p","576p","720p","1080p","1440p","2160p","4320p")
        val heights=listOf(480,576,720,1080,1440,2160,4320)
        val dynamicRanges=listOf("sdr","hdr10","hlg","dolby_vision")
        val videoCodecs=listOf("h264","hevc","av1","vp9","mpeg2video")
        val audioCodecs=listOf("aac","ac3","eac3","dts","truehd","flac","opus","mp3","pcm_s16le")
    }
}

/** Registered three-letter tags remain distinct: Bokmal/nob is not silently changed into nor. */
object SourceFilterLanguages {
    private val registered:Set<String> by lazy {
        val raw=requireNotNull(javaClass.getResourceAsStream("/source-filter-language-codes.json")){"Source filter language registry missing"}.bufferedReader().use {it.readText()}
        ApiJson.parseToJsonElement(raw).jsonArray.map {it.jsonPrimitive.content}.toSet()
    }
    private val aliases=mapOf("fre" to "fra","ger" to "deu","dut" to "nld","chi" to "zho","cze" to "ces","wel" to "cym","ice" to "isl","rum" to "ron","gre" to "ell","alb" to "sqi","slo" to "slk","baq" to "eus","may" to "msa","per" to "fas","arm" to "hye","geo" to "kat","bur" to "mya","mac" to "mkd","tib" to "bod")
    fun canonical(raw:String?):String? {
        val value=raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        if(value in setOf("und","mul","zxx") || !Regex("[a-z]{2,3}").matches(value))return null
        aliases[value]?.let {return it}
        if(value.length==3)return value.takeIf {it in registered}
        return runCatching {Locale.forLanguageTag(value).isO3Language.lowercase(Locale.ROOT)}.getOrNull()?.takeIf {it in registered}
    }
    fun label(code:String):String=Locale.forLanguageTag(code).displayLanguage.takeIf {it.isNotBlank() && it!=code} ?: code.uppercase(Locale.ROOT)
    fun choices(extra:Collection<String> = emptyList()):List<String> =
        (Locale.getISOLanguages().mapNotNull(::canonical)+Locale.getAvailableLocales().mapNotNull {canonical(it.language)}+extra.mapNotNull(::canonical)).distinct().sortedBy {label(it).lowercase(Locale.getDefault())}
}
