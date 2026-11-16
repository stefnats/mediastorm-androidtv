package com.strmr.tv.sources

/** Shared TV/phone options; no choice evaluates or hides a local result page. */
object SourceFilterMenus {
    enum class Field { BASIS,AUDIO,SUBTITLE,RESOLUTION,VIDEO,HDR,AUDIO_CODEC,AD,UNKNOWN }
    data class Choice(val value:String?,val label:String)
    fun fields(filters:SourceFilters)=listOf(Field.BASIS,Field.AUDIO,Field.SUBTITLE,Field.RESOLUTION,Field.VIDEO,Field.HDR)+
        (if(filters.basis==SourceFilterBasis.PROBED)listOf(Field.AUDIO_CODEC,Field.AD) else emptyList())+Field.UNKNOWN
    fun title(field:Field,filters:SourceFilters)=when(field) {
        Field.BASIS->"Filter basis";Field.AUDIO->if(filters.basis==SourceFilterBasis.CANDIDATE)"Audio language claim" else "Audio track tag"
        Field.SUBTITLE->if(filters.basis==SourceFilterBasis.CANDIDATE)"Subtitle language claim" else "Subtitle track tag"
        Field.RESOLUTION->if(filters.basis==SourceFilterBasis.CANDIDATE)"Claimed resolution" else "Frame height"
        Field.VIDEO->"Video codec";Field.HDR->"Dynamic range";Field.AUDIO_CODEC->"Audio codec";Field.AD->"Audio description tag";Field.UNKNOWN->"Unknown metadata"
    }
    fun choices(field:Field,filters:SourceFilters,languages:List<String>)=when(field) {
        Field.BASIS->listOf(Choice("candidate","Release claims"),Choice("probed","Existing file metadata"))
        Field.AUDIO,Field.SUBTITLE->listOf(Choice(null,"Any language"))+languages.map {Choice(it,SourceFilterLanguages.label(it))}
        Field.RESOLUTION->if(filters.basis==SourceFilterBasis.CANDIDATE)listOf(Choice(null,"Any claimed resolution"))+(SourceFilters.resolutions+listOfNotNull(filters.claimedResolutionAtLeast)).distinct().sortedBy {it.dropLast(1).toInt()}.map {Choice(it,"At least $it")} else listOf(Choice(null,"Any frame height"))+(SourceFilters.heights+listOfNotNull(filters.heightAtLeast)).distinct().sorted().map {Choice(it.toString(),"At least $it pixels")}
        Field.VIDEO->listOf(Choice(null,"Any video codec"))+(SourceFilters.videoCodecs+listOfNotNull(filters.videoCodec)).distinct().map {Choice(it,it.uppercase())}
        Field.HDR->listOf(Choice(null,"Any dynamic range"))+SourceFilters.dynamicRanges.map {Choice(it,it.replace('_',' ').uppercase())}
        Field.AUDIO_CODEC->listOf(Choice(null,"Any audio codec"))+(SourceFilters.audioCodecs+listOfNotNull(filters.audioCodec)).distinct().map {Choice(it,it.uppercase())}
        Field.AD->listOf(Choice(null,"Any AD tag"),Choice("required","AD tag required"))
        Field.UNKNOWN->listOf(Choice("include","Include unknown metadata"),Choice("exclude","Known matches only"))
    }
    fun value(field:Field,filters:SourceFilters):String?=when(field) {
        Field.BASIS->if(filters.basis==SourceFilterBasis.CANDIDATE)"candidate" else "probed"
        Field.AUDIO->filters.audioLanguage;Field.SUBTITLE->filters.subtitleLanguage
        Field.RESOLUTION->if(filters.basis==SourceFilterBasis.CANDIDATE)filters.claimedResolutionAtLeast else filters.heightAtLeast?.toString()
        Field.VIDEO->filters.videoCodec;Field.HDR->filters.dynamicRange;Field.AUDIO_CODEC->filters.audioCodec
        Field.AD->if(filters.audioDescription==true)"required" else null
        Field.UNKNOWN->if(filters.unknown==SourceFilterUnknown.INCLUDE)"include" else "exclude"
    }
    fun choose(field:Field,value:String?,filters:SourceFilters):SourceFilters=when(field) {
        Field.BASIS->filters.withBasis(if(value=="probed")SourceFilterBasis.PROBED else SourceFilterBasis.CANDIDATE)
        Field.AUDIO->filters.copy(audioLanguage=value);Field.SUBTITLE->filters.copy(subtitleLanguage=value)
        Field.RESOLUTION->if(filters.basis==SourceFilterBasis.CANDIDATE)filters.copy(claimedResolutionAtLeast=value)else filters.copy(heightAtLeast=value?.toIntOrNull())
        Field.VIDEO->filters.copy(videoCodec=value);Field.HDR->filters.copy(dynamicRange=value);Field.AUDIO_CODEC->filters.copy(audioCodec=value)
        Field.AD->filters.copy(audioDescription=if(value=="required")true else null)
        Field.UNKNOWN->filters.copy(unknown=if(value=="exclude")SourceFilterUnknown.EXCLUDE else SourceFilterUnknown.INCLUDE)
    }
    fun rowLabel(row:SourceSearchRow,state:SourceSearchState):String?=when {
        !state.supported->null
        state.expiredNow->"Filter data expired"
        state.metadataChanged->"File metadata changed; apply filters again"
        row.verdict==SourceSearchVerdict.UNKNOWN->"Required metadata is unknown"
        row.verdict==SourceSearchVerdict.MATCH->if(state.applied.basis==SourceFilterBasis.CANDIDATE)"Matches release claims" else "Matches file metadata"
        else->null
    }
    fun summary(state:SourceSearchState):String?=when {
        !state.supported->null
        state.expiredNow->"Filter data expired. Apply filters again."
        state.metadataChanged->"Track metadata changed. Apply filters to update these results."
        state.loading || state.error!=null->null
        state.applied.active->"${state.matchedTotal} metadata matches · ${state.unknownTotal} unknown${if(state.applied.unknown==SourceFilterUnknown.EXCLUDE)" (hidden)" else ""}"
        else->"${state.total} sources in these search results"
    }
}
