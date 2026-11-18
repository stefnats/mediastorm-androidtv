package com.strmr.tv.ui.details

import com.strmr.tv.api.*
import com.strmr.tv.library.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContentPreferenceOriginContextTest {
    private val target = DetailsTarget("tmdb:series:123", "Synthetic", false)
    private val episode = Episode("episode", seasonNumber = 2, episodeNumber = 3, numbering = EpisodeNumbering(target.titleId, "official"))
    private val scopes = contentLocalScopes(target, episode = episode)
    private suspend fun TestScope.request(parent: Job, selected: () -> Boolean = { true }): ContentLocalRequest {
        val origin = TitleListOrigin(target, ProfileDataContext.current, parent, selected)
        var request: ContentLocalRequest? = null
        val action = TitleListAction(origin, backgroundScope) { op -> request = ContentLocalRequest.capture(target, scopes, op, origin, { request }, selected) }
        action.commit(action.accept()!!) { origin.close() }!!.join()
        assertFalse(origin.isCurrent)
        return requireNotNull(request)
    }
    private fun TestScope.editor(request: ContentLocalRequest, onSaved: () -> Unit = {},
        read: suspend (ContentLocalScope, Long) -> ContentLocalPreference = { _, _ -> ContentLocalPreference(null, true) },
        write: suspend (ContentLocalScope, Boolean?, Long) -> ContentLocalPreference = { _, value, _ -> ContentLocalPreference(value, true) },
    ) = ContentLocalPreferences(backgroundScope, request.scopes, ProfileDataContext.changes, read, write, onSaved, request.context, request)

    @Test fun `normal accepted dismissal preserves original immutable scopes and inherit null`() = runTest {
        val parent = Job(); val request = request(parent); var saved = 0
        val writes = mutableListOf<Pair<ContentLocalScope, Boolean?>>()
        val editor = editor(request, { saved++ }, write = { scope, value, context ->
            assertEquals(request.context, context); writes += scope to value; ContentLocalPreference(value, true)
        })
        assertSame(target, request.target); assertTrue(request.isCurrent())
        editor.load(); runCurrent(); editor.select(request.scopes.last()); editor.choose(LocalSourceChoice.INHERIT); runCurrent()
        assertEquals(listOf(request.scopes.last() to null), writes)
        assertEquals(1, saved); assertTrue(editor.state.value.closed); assertFalse(request.isCurrent()); assertTrue(parent.isActive); parent.cancel()
    }
    @Test fun `held phone hide cannot capture a request after original equal-profile page retirement`() = runTest {
        val parent = Job(); var selected = true; var opened = 0
        val origin = TitleListOrigin(target, ProfileDataContext.current, parent) { selected }
        val action = TitleListAction(origin, backgroundScope) { op ->
            ContentLocalRequest.capture(target, scopes, op, origin, { null }) { selected }?.also { opened++ }
        }
        val click = action.accept()!!; val hidden = CompletableDeferred<Unit>()
        val pending = launch { commitTitleListClick({ hidden.await() }, click::abandon) { action.commit(click) { origin.close() } } }
        runCurrent(); selected = false; hidden.complete(Unit); pending.join()
        assertEquals(0, opened); parent.cancel()
    }
    @Test fun `cancelled hide never runs accepted source action`() = runTest {
        val parent = Job(); var opened = 0
        val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val action = TitleListAction(origin, backgroundScope) { opened++ }; val click = action.accept()!!
        assertTrue(runCatching { commitTitleListClick({ throw CancellationException() }, click::abandon) { action.commit(click) {} } }.exceptionOrNull() is CancellationException)
        assertEquals(0, opened); parent.cancel()
    }
    @Test fun `loaded editor on a covered same-profile page starts no write and publishes no callback`() = runTest {
        val parent = Job(); var selected = true; var writes = 0; var saved = 0
        val request = request(parent) { selected }
        val editor = editor(request, { saved++ }, write = { _, v, _ -> writes++; ContentLocalPreference(v, true) })
        editor.load(); runCurrent(); selected = false
        editor.choose(LocalSourceChoice.LOCAL); runCurrent()
        assertEquals(0, writes); assertEquals(0, saved); assertFalse(request.isCurrent()); editor.close(); parent.cancel()
    }
    @Test fun `queued choice checks original selection again before actual write`() = runTest {
        val parent = Job(); var selected = true; var writes = 0
        val request = request(parent) { selected }
        val editor = editor(request, write = { _, v, _ -> writes++; ContentLocalPreference(v, true) })
        editor.load(); runCurrent(); editor.choose(LocalSourceChoice.NORMAL); selected = false; runCurrent()
        assertEquals(0, writes); editor.close(); parent.cancel()
    }
    @Test fun `new equal episode instance retires the originally selected episode request`() = runTest {
        val parent = Job(); var currentEpisode = episode; var reads = 0
        val request = request(parent) { currentEpisode === episode }
        currentEpisode = episode.copy()
        val editor = editor(request, read = { _, _ -> reads++; ContentLocalPreference(null, true) })
        editor.load(); runCurrent(); assertEquals(0, reads); assertFalse(request.isCurrent()); editor.close(); parent.cancel()
    }
    @Test fun `old request callbacks cannot close or publish into a replacement request`() = runTest {
        val parent = Job(); val a = request(parent); val b = request(parent)
        var active: ContentLocalRequest? = b; var saved = 0
        assertFalse(a.dismissIfSelected({ active }) { active = null })
        assertFalse(a.publishIfSelected({ active }) { saved++ })
        assertSame(b, active); assertEquals(0, saved)
        assertTrue(b.publishIfSelected({ active }) { saved++ }); assertEquals(1, saved)
        assertTrue(b.dismissIfSelected({ active }) { active = null }); assertNull(active); a.close(); parent.cancel()
    }
    @Test fun `masked late accepted write cannot refresh toast or warm a retired original page`() = runTest {
        val parent = Job(); var selected = true; var saved = 0; val reply = CompletableDeferred<Unit>()
        val request = request(parent) { selected }
        val editor = editor(request, { saved++ }, write = { _, v, _ -> withContext(NonCancellable) { reply.await() }; ContentLocalPreference(v, true) })
        editor.load(); runCurrent(); editor.choose(LocalSourceChoice.NORMAL); runCurrent()
        selected = false; reply.complete(Unit); runCurrent()
        assertEquals(0, saved); assertTrue(editor.state.value.values.values.all { it == null }); editor.close(); parent.cancel()
    }
    @Test fun `read stage retirement prevents subsequent scope reads`() = runTest {
        val parent = Job(); var selected = true; var reads = 0
        val request = request(parent) { selected }
        val editor = editor(request, read = { _, _ -> reads++; selected = false; ContentLocalPreference(null, true) })
        editor.load(); runCurrent(); assertEquals(1, reads); assertFalse(editor.state.value.supported); editor.close(); parent.cancel()
    }
    @Test fun `original A B A request never follows the new global epoch`() = runTest {
        val parent = Job(); val request = request(parent); var reads = 0
        ProfileDataContext.invalidate(); ProfileDataContext.invalidate()
        val editor = editor(request, read = { _, _ -> reads++; ContentLocalPreference(null, true) })
        editor.load(); runCurrent(); assertEquals(0, reads); assertFalse(request.isCurrent()); editor.close(); parent.cancel()
    }
    @Test fun `request factory rejects an equal-valued replacement target and copies the selected scope list`() = runTest {
        val parent = Job(); val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
        val operations = CustomListOperations(ProfileDataContext.current, target)
        val op = operations.begin()!!
        assertTrue(runCatching { operations.run(op) { ContentLocalRequest.capture(target.copy(), scopes, it, origin, { null }) { true } } }.exceptionOrNull() is IllegalArgumentException)
        val mutable = scopes.toMutableList()
        val request = operations.run(op) { ContentLocalRequest.capture(target, mutable, it, origin, { null }) { true } }!!
        mutable.clear()
        assertEquals(scopes, request.scopes)
        assertSame(target, request.target)
        request.close(); operations.close(); origin.close(); parent.cancel()
    }

    @Test fun `replaced request blocks old editor IO before disposal and preserves the new positive selection`() = runTest {
        val parent = Job(); var active: ContentLocalRequest? = null
        suspend fun open(): ContentLocalRequest {
            val origin = TitleListOrigin(target, ProfileDataContext.current, parent)
            val action = TitleListAction(origin, backgroundScope) { op ->
                ContentLocalRequest.capture(target, scopes, op, origin, { active }) { true }?.let { active = it }
            }
            action.commit(action.accept()!!) { origin.close() }!!.join()
            return requireNotNull(active)
        }
        val a = open(); var oldReads = 0; var oldWrites = 0
        val old = editor(a, read = { _, _ -> oldReads++; ContentLocalPreference(null, true) },
            write = { _, v, _ -> oldWrites++; ContentLocalPreference(v, true) })
        old.load(); runCurrent(); val alreadyRead = oldReads
        // The host replaced its slot; the old editor has not yet received onDispose.
        active = null
        val b = open()
        old.load(); old.choose(LocalSourceChoice.NORMAL); runCurrent()
        assertEquals(alreadyRead, oldReads); assertEquals(0, oldWrites); assertFalse(a.isCurrent()); assertTrue(b.isCurrent())
        var saved = 0; val fresh = editor(b, { saved++ })
        fresh.load(); runCurrent(); fresh.choose(LocalSourceChoice.INHERIT); runCurrent()
        assertEquals(1, saved); old.close(); parent.cancel()
    }


}
