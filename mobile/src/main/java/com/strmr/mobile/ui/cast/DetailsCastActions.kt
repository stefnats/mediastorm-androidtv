package com.strmr.mobile.ui.cast

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import com.strmr.mobile.cast.CastController
import com.strmr.mobile.cast.arm
import com.strmr.mobile.ui.components.SheetAction
import com.strmr.mobile.ui.details.DetailsActionContext
import com.strmr.mobile.ui.details.DetailsExtraActions
import com.strmr.tv.api.Episode
import com.strmr.tv.player.PlaybackSessionContext
import com.strmr.mobile.cast.castContextEligible

/**
 * Cast on the details page (the iPhone page's Cast to Device…): a Cast button in the top bar, a
 * row in More and in an episode's sheet. Each opens the picker; the chosen device is armed and
 * Play's usual resolve (prequeue or library copy, with the page's track picks) goes to it
 * instead of the phone's player.
 */
object DetailsCastActions : DetailsExtraActions {
    @Composable
    override fun RowScope.TopBar(context: DetailsActionContext) {
        val casting = CastController.session != null
        IconButton(onClick = { castPrimary(context) },enabled=context.playbackCurrent && runCatching {castContextEligible(PlaybackSessionContext.capture(context.model.dataContext))}.getOrDefault(false)) {
            Icon(if (casting) Icons.Filled.CastConnected else Icons.Filled.Cast, contentDescription = "Cast")
        }
    }

    override fun moreActions(context: DetailsActionContext): List<SheetAction> =
        listOf(SheetAction("Cast to Device…", Icons.Filled.Cast) { castPrimary(context) })

    override fun episodeActions(context: DetailsActionContext, episode: Episode): List<SheetAction> =
        listOf(
            SheetAction("Cast to Device…", Icons.Filled.Cast) {
                if(context.playbackCurrent) {
                    val original=PlaybackSessionContext.capture(context.model.dataContext)
                    if(castContextEligible(original))CastUi.openPicker { device ->
                        if(context.playbackCurrent && castContextEligible(original))CastController.arm(device,original) {context.model.play(episode,context.model.resumePoint(episode))}
                    }
                }
            },
        )

    private fun castPrimary(context: DetailsActionContext) {
        if(!context.playbackCurrent)return
        val original=PlaybackSessionContext.capture(context.model.dataContext)
        if(!castContextEligible(original))return
        CastUi.openPicker { device ->if(context.playbackCurrent && castContextEligible(original))CastController.arm(device,original) { context.playPrimary() } }
    }
}
