package com.strmr.tv.player

import com.strmr.tv.api.ApiError
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A viewer's explicit choice; neither this prompt nor episode labels authorize a source. */
class CombinedLibraryFilePrompt internal constructor(val title: String, val message: String) {
    internal val answer = CompletableDeferred<Boolean>()
}

/** One pending library-file choice shared by the actual TV and mobile overlays. */
class CombinedLibraryFileConsent internal constructor(private val contexts: StateFlow<Long>) {
    constructor() : this(ProfileDataContext.changes)
    private val pending = MutableStateFlow<CombinedLibraryFilePrompt?>(null)
    val prompt: StateFlow<CombinedLibraryFilePrompt?> = pending

    internal suspend fun ask(title: String, message: String, expectedContext: Long): Boolean = coroutineScope {
        if (contexts.value != expectedContext) throw CancellationException("Profile data context changed")
        check(pending.value == null) { "A combined file confirmation is already open" }
        val request = CombinedLibraryFilePrompt(title, message)
        pending.value = request
        val changed = launch {
            contexts.first { it != expectedContext }
            request.answer.cancel(CancellationException("Profile data context changed"))
        }
        try {
            withTimeoutOrNull(60_000L) { request.answer.await() }
                ?: throw ApiError.NotReady("The combined file confirmation expired. Select Play again.")
        } finally {
            changed.cancel()
            if (pending.value === request) pending.value = null
        }
    }

    fun answer(request: CombinedLibraryFilePrompt, allow: Boolean) {
        if (pending.value === request) request.answer.complete(allow)
    }
}
