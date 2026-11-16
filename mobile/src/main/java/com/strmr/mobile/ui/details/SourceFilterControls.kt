package com.strmr.mobile.ui.details

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import com.strmr.mobile.ui.sports.PickerDialog
import com.strmr.tv.sources.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceFilterControls(state:SourceSearchState,onApply:()->Unit,onReload:()->Unit) {
    if(!state.supported)return
    if(state.vodDenied!=null) {Text(state.vodDenied!!.message);return}
    var field by remember(state) {mutableStateOf<SourceFilterMenus.Field?>(null)}
    val filters=state.draft
    val languages=remember(state.rows,filters.audioLanguage,filters.subtitleLanguage) {
        val observed=state.rows.flatMap {row ->
            val evidence=row.mediaEvidence ?: row.result.mediaEvidence
            evidence?.candidate?.audioLanguages.orEmpty()+evidence?.candidate?.subtitleLanguages.orEmpty()+
                evidence?.probed?.audioStreams.orEmpty().mapNotNull {it.language}+evidence?.probed?.subtitleStreams.orEmpty().mapNotNull {it.language}
        }
        SourceFilterLanguages.choices(observed+listOfNotNull(filters.audioLanguage,filters.subtitleLanguage))
    }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Filter streams")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            for(f in SourceFilterMenus.fields(filters)) {
                val choices=SourceFilterMenus.choices(f,filters,languages)
                val selected=choices.firstOrNull {it.value==SourceFilterMenus.value(f,filters)}?.label.orEmpty()
                OutlinedButton(onClick={field=f},enabled=!state.loading && !state.requiresReload) {Text("${SourceFilterMenus.title(f,filters)}: $selected")}
            }
        }
        Text(if(filters.basis==SourceFilterBasis.CANDIDATE)"Release claims are unverified." else "File metadata describes track tags and input format, not spoken content or device output.")
        if(state.filterPending)Text("Filter changes have not been applied.")
        if(state.metadataChanged)Text("Track metadata changed. Apply filters to update matches.")
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick=onApply,enabled=!state.loading && !state.requiresReload) {Text("Apply filters")}
            OutlinedButton(onClick={state.clearDraft();onApply()},enabled=!state.loading && !state.requiresReload) {Text("Clear filters")}
            OutlinedButton(onClick=onReload,enabled=!state.loading) {Text("Reload source list")}
        }
        SourceFilterMenus.summary(state)?.let {Text(it)}
        if(state.truncated)Text("Search result limit reached; other sources may exist.")
    }
    field?.let {f ->
        val options=SourceFilterMenus.choices(f,filters,languages)
        val source=state.sourceGeneration
        PickerDialog(SourceFilterMenus.title(f,filters),options.map {it.label},options.indexOfFirst {it.value==SourceFilterMenus.value(f,filters)},
            onPick={index->if(state.isCurrent && state.supported && !state.loading && !state.requiresReload && source==state.sourceGeneration && state.draft==filters)state.edit(SourceFilterMenus.choose(f,options[index].value,state.draft))},onDismiss={field=null})
    }
}
