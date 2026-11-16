package com.strmr.tv.ui.live

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.strmr.tv.live.LiveStore
import com.strmr.tv.ui.PageMargin

@Composable
fun StationFavoriteButton(store: LiveStore, id: String) {
    val action=store.stationAction(id) ?: return
    OutlinedButton(enabled=!action.pending,onClick={store.setStationFavorite(action)},modifier=Modifier.semantics {
        stateDescription=if(action.favorite)"Station favorite" else "Not a station favorite"
    }) {
        Icon(Icons.Filled.Star,contentDescription=action.label,tint=if(action.favorite)Color(0xFFFFD60A)else Color.White.copy(alpha=0.5f),modifier=Modifier.size(18.dp))
    }
}

@Composable
fun StationFavoriteStatus(store: LiveStore) {
    val error=store.stationFavoriteError
    if(error!=null) Row(Modifier.fillMaxWidth().padding(horizontal=PageMargin,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(error,color=Color(0xFFFF6B6B),modifier=Modifier.weight(1f))
        OutlinedButton(enabled=!store.stationFavoriteBusy,onClick={if(store.stationFavoriteSettingsFailed)store.retryFavoriteSettings()else store.retryStationFavorite()}) {
            Text(if(store.stationFavoriteSettingsFailed)"Retry favorite settings" else "Retry station favorite")
        }
    } else if(store.stationFavoriteSaving) Text("Saving station favorites…",modifier=Modifier.padding(horizontal=PageMargin,vertical=4.dp))
}
