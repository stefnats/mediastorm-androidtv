package com.strmr.mobile.ui.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.strmr.mobile.downloads.OfflineAccess
import com.strmr.mobile.downloads.OfflineContext
import com.strmr.tv.app.AppSession
import com.strmr.tv.library.ProfileDataContext

/** Re-evaluate synchronous projections before rendering a replacement profile's frame. */
@Composable
internal fun offlineUiContext():OfflineContext? {
    ProfileDataContext.changes.collectAsState().value
    AppSession.phase.collectAsState().value
    AppSession.profile.collectAsState().value
    AppSession.pinChallenge.collectAsState().value
    return OfflineAccess.current()
}
