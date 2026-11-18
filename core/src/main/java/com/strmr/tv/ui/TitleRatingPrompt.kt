package com.strmr.tv.ui

import androidx.compose.runtime.*
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.app.AppSession
import com.strmr.tv.library.CustomListSelectionKey
import com.strmr.tv.library.TitleListAction
import com.strmr.tv.library.TitleListOrigin
import com.strmr.tv.ratings.ServerTitleRatingGateway
import com.strmr.tv.ratings.TitleRatingEditor
import com.strmr.tv.ratings.TitleRatingGateway
import kotlinx.coroutines.CoroutineScope

/** A shell-owned prompt outlives the long-press menu that opened it. */
object TitleRatingPrompt {
    var editor by mutableStateOf<TitleRatingEditor?>(null)
        private set
    fun open(value: TitleRatingEditor) {
        if (!value.isCurrent()) return
        editor?.close()
        editor = value
    }
    /** A queued Done or retirement effect may close only its actual prompt. */
    fun dismiss(expected: TitleRatingEditor) {
        if (editor !== expected) return
        expected.close()
        editor = null
    }
}

/** The accepted receipt detaches from normal menu dismissal, retaining its original producer. */
internal fun originalRatingAction(
    target: DetailsTarget,
    origin: TitleListOrigin,
    scope: CoroutineScope,
    gateway: () -> TitleRatingGateway = { ServerTitleRatingGateway() },
    open: (TitleRatingEditor) -> Unit = TitleRatingPrompt::open,
): TitleListAction = TitleListAction(origin, scope) { operation ->
    operation.requireTarget(target)
    val server = operation.owner.use(gateway)
    operation.requireTarget(target)
    val editor = TitleRatingEditor(target, server, parent = origin.parent,
        selectionCurrent = { operation.owner.isCurrent() && origin.selectionCurrent() })
    operation.publish { if (editor.isCurrent()) open(editor) else editor.close() }
}

/** No gateway is captured from a new global context during deferred menu composition. */
@Composable
fun rememberRatingAction(target: DetailsTarget, origin: TitleListOrigin, scope: CoroutineScope): TitleListAction =
    remember(CustomListSelectionKey(target), origin, scope) { originalRatingAction(target, origin, scope) }

/** Profile switches and sign-out hide the prompt before old responses can land. */
@Composable
fun currentRatingEditor(): TitleRatingEditor? {
    val profile by AppSession.profile.collectAsState()
    val server by AppSession.serverUrl.collectAsState()
    val phase by AppSession.phase.collectAsState()
    val editor = TitleRatingPrompt.editor ?: return null
    val current = profile != null && server != null && phase == AppSession.Phase.Ready && editor.isCurrent()
    LaunchedEffect(editor, current) { if (!current) TitleRatingPrompt.dismiss(editor) }
    if (!current) return null
    DisposableEffect(editor) { onDispose { editor.close() } }
    LaunchedEffect(editor) { editor.load() }
    return editor
}
