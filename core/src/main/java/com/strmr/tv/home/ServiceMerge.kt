package com.strmr.tv.home

import com.strmr.tv.api.ApiClient
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val mergedPages = ServicePageMerge()

/** The production adapter passes the original owner to every actual child request. */
internal suspend fun ApiClient.mergedServicePage(
    list: BrowseList.Service, limit: Int, offset: Int, extra: Map<String, String>, options: BrowseOptions?,
    expectedContext: Long,
): BrowsePage = mergedPages.read(list, limit, offset, extra, options, expectedContext) { part, count, start, query, selected, expected ->
    browsePage(part, count, start, query, options = selected, expectedContext = expected)
}

/** Interleave the same owner's lists; cached totals are pagination hints, not content authority. */
internal class ServicePageMerge {
    private val totalsLock = Any()
    private var totalsOwner: Long? = null
    private val mergedTotals = mutableMapOf<String, Int>()

    suspend fun read(
        list: BrowseList.Service, limit: Int, offset: Int, extra: Map<String, String>, options: BrowseOptions?,
        expectedContext: Long,
        readPage: suspend (BrowseList.Service, Int, Int, Map<String, String>, BrowseOptions?, Long) -> BrowsePage,
    ): BrowsePage = coroutineScope {
        suspend fun checkOwner() {
            currentCoroutineContext().ensureActive()
            ProfileDataContext.requireCurrent(expectedContext)
        }
        checkOwner()
        val parts = list.service.lists.indices.map { list.copy(part = it + 1) }
        // Quick-page flags change speed, not titles. The immutable owner scopes every key.
        val keys = parts.map { part ->
            (part.query + extra - setOf("lite", "deferArtwork") + options?.query(part).orEmpty()).toSortedMap().toString()
        }
        var totals = synchronized(totalsLock) {
            ProfileDataContext.requireCurrent(expectedContext)
            if (totalsOwner != expectedContext) {
                mergedTotals.clear()
                totalsOwner = expectedContext
            }
            keys.map { mergedTotals[it] }.takeIf { null !in it }?.map { it!! }
        }
        suspend fun page(part: BrowseList.Service, count: Int, start: Int): BrowsePage {
            checkOwner()
            return readPage(part, count, start, extra, options, expectedContext).also { checkOwner() }
        }
        var firstPages: List<BrowsePage>? = null
        if (offset == 0 || totals == null) {
            val pages = parts.map { async { page(it, maxOf(limit, 1), 0) } }.awaitAll()
            checkOwner()
            synchronized(totalsLock) {
                ProfileDataContext.requireCurrent(expectedContext)
                if (totalsOwner == expectedContext) keys.zip(pages).forEach { (key, value) -> mergedTotals[key] = value.total }
            }
            totals = pages.map { it.total }
            firstPages = pages
        }
        val window = interleave(totals, offset, limit)
        val pages = if (firstPages != null && offset == 0) firstPages else parts.indices.map { i ->
            async {
                checkOwner()
                val (start, count) = window.ranges[i]
                if (count == 0) BrowsePage(emptyList(), 0) else page(parts[i], count, start)
            }
        }.awaitAll()
        checkOwner()
        val cursors = IntArray(parts.size)
        val entries = window.order.mapNotNull { source ->
            pages[source].entries.getOrNull(cursors[source])?.also { cursors[source]++ }
        }
        BrowsePage(entries, totals.sum(), pages.flatMap { it.genres }.distinct().sorted(), pages.flatMap { it.letters }.distinct().sorted())
    }
}

internal class MergeWindow(val order: List<Int>, val ranges: List<Pair<Int, Int>>)

/** The lists of the merged positions `offset until offset + limit`, in order, and the run (start, count) of each list's own titles they cover. */
internal fun interleave(totals: List<Int>, offset: Int, limit: Int): MergeWindow {
    val order = mutableListOf<Int>()
    val starts = IntArray(totals.size)
    val counts = IntArray(totals.size)
    val end = offset + limit
    var position = 0
    var round = 0
    while (position < end && totals.any { it > round }) {
        for ((source, total) in totals.withIndex()) {
            if (total <= round || position >= end) continue
            if (position < offset) starts[source]++ else { order += source; counts[source]++ }
            position++
        }
        round++
    }
    return MergeWindow(order, totals.indices.map { starts[it] to counts[it] })
}
