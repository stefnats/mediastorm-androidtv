package com.strmr.mobile.ui.live

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.strmr.tv.live.LiveStore

@Composable
fun StationFavoriteButton(store: LiveStore, id: String) {
    val action=store.stationAction(id) ?: return
    OutlinedButton(enabled=!action.pending,onClick={store.setStationFavorite(action)},modifier=Modifier.semantics {
        stateDescription=if(action.favorite)"Station favorite" else "Not a station favorite"
    }) {
        Icon(Icons.Filled.Star,contentDescription=action.label,tint=if(action.favorite)FavoriteYellow else MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.size(18.dp))
    }
}

@Composable
fun StationFavoriteStatus(store: LiveStore) {
    val error=store.stationFavoriteError
    if(error!=null) Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
        OutlinedButton(enabled=!store.stationFavoriteBusy,onClick={if(store.stationFavoriteSettingsFailed)store.retryFavoriteSettings()else store.retryStationFavorite()}) {
            Text(if(store.stationFavoriteSettingsFailed)"Retry favorite settings" else "Retry station favorite")
        }
    } else if(store.stationFavoriteSaving) Text("Saving station favorites…",modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp))
}
