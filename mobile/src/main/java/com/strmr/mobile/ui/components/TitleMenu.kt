package com.strmr.mobile.ui.components

import kotlinx.coroutines.Job
import androidx.compose.runtime.referentialEqualityPolicy
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.library.TitleMenuSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.strmr.mobile.ui.details.WatchStateLoader
import com.strmr.mobile.ui.library.rememberLibraryOpener
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.home.HomeCard
import com.strmr.tv.home.HomeLink
import com.strmr.tv.library.CustomListsStore

/**
 * A title's long-press menu (Expo's long-press actions: watchlist, watched, add to list, hide,
 * set aside, …), shown as a bottom sheet over whichever screen asked. Any screen calls
 * [show]; [TitleMenuHost] in the shell draws it, and the sheets it opens. Main thread only.
 */
object TitleMenu {
    private var storedSelection by mutableStateOf<TitleMenuSelection?>(null, referentialEqualityPolicy())
    val selection get() = storedSelection?.takeIf { it.isCurrent }
    val card get() = selection?.card
    val continueWatching get() = selection?.continueWatching == true
    var watchStateFor by mutableStateOf<DetailsTarget?>(null)

    fun show(card: HomeCard, expectedContext: Long, parent: Job, continueWatching: Boolean = false) {
        if (expectedContext != ProfileDataContext.current) return
        if (card.target != null || card.link is HomeLink.Library) {
            val next = TitleMenuSelection(card, expectedContext, parent, continueWatching)
            if (!next.isCurrent) return
            storedSelection?.close()
            storedSelection = next
        }
    }

    fun dismiss(expected: TitleMenuSelection? = storedSelection) {
        if (storedSelection === expected) {
            storedSelection?.close()
            storedSelection = null
        }
    }

}

/**
 * The shell's host for the title menu and the sheets any screen opens through shared state:
 * Manage Lists ([CustomListsStore.picking]) and a series' Watch State ([TitleMenu.watchStateFor]).
 * Actions run in this host's scope, which outlives the sheets.
 */
@Composable
fun TitleMenuHost() {
    val scope = rememberCoroutineScope()
    // Here, not in the sheet: a library title's copy choice and resume prompt outlive the sheet.
    val openLibrary = rememberLibraryOpener()
    TitleMenu.selection?.let { selection ->
        TitleMenuSheet(selection, scope, openLibrary, onDismiss = { TitleMenu.dismiss(selection) })
    }
    CustomListsStore.picking?.let { target ->
        val owner = CustomListsStore.pickingOwner(target)
        AddToListSheet(target, onDismiss = { if (owner?.isCurrent() == true) CustomListsStore.clearPicking(target, owner.epoch) })
    }
    TitleMenu.watchStateFor?.let { target -> WatchStateLoader(target, onDismiss = { TitleMenu.watchStateFor = null }) }
}
