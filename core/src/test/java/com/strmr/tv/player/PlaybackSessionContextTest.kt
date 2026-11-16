package com.strmr.tv.player

import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.library.ProfileDataContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Real epoch/route boundaries without Android storage, device or provider IO.
 * Platform and Cast integration are exercised by their separate lifecycle tests.
 */
class PlaybackSessionContextTest {
    private val originalUrl = ApiClient.baseUrl
    private val originalCredential = ApiClient.remoteCredential

    @Before fun retirePreviousWork() { ProfileDataContext.invalidate() }
    @After fun restoreRoute() {
        ApiClient.baseUrl = originalUrl
        ApiClient.remoteCredential = originalCredential
        ProfileDataContext.invalidate()
    }

    private fun session(owner: PlaybackSessionContext? = PlaybackSessionContext.capture()) =
        PlayerSession(
            PlaybackRequest(DetailsTarget("tmdb:movie:42", "Synthetic movie", true)),
            "https://example.invalid/synthetic-stream.mkv",
            null,
            ownership = owner,
        )

    private fun cancelled(action: () -> Unit) {
        try { action(); fail("A retired playback owner was accepted") }
        catch (_: CancellationException) {}
    }

    @Test fun freshPreparationRetainsItsExactEpochAndProfileWithoutGrantingContent() {
        val expected = ProfileDataContext.current
        val binding = PlaybackSessionContext.capture(expected)
        assertEquals(expected, binding.epoch)
        assertEquals(ApiClient.profileId, binding.profileId)
        assertTrue(binding.isCurrent())
        assertTrue(session(binding).canOpenPlayer())
        assertEquals("captured action", binding.use { "captured action" })
    }

    @Test fun anOldPreparationCannotCaptureTheReplacementContext() {
        val expected = ProfileDataContext.current
        ProfileDataContext.invalidate()
        cancelled { PlaybackSessionContext.capture(expected) }
        assertTrue(PlaybackSessionContext.capture().isCurrent())
    }

    @Test fun pendingOnlineSessionCannotEnterAPlayerAfterContextChanged() {
        val pending = session()
        ProfileDataContext.invalidate()
        assertFalse(pending.canOpenPlayer())
        assertFalse(pending.playbackAllowed())
        assertTrue(session().canOpenPlayer())
    }

    @Test fun returningToTheSameVisibleProfileCannotReviveAnEarlierEpoch() {
        val originalProfile = ApiClient.profileId
        val pending = session()
        ProfileDataContext.invalidate()
        ProfileDataContext.invalidate()
        assertEquals(originalProfile, ApiClient.profileId)
        assertFalse(pending.canOpenPlayer())
        cancelled { pending.ownership!!.requireCurrent() }
    }

    @Test fun copyingASessionToAnotherTitleOrUrlRetainsItsOriginalOwner() {
        val pending = session()
        val replacementTitle = pending.copy(
            request = PlaybackRequest(DetailsTarget("tmdb:movie:99", "Other synthetic movie", true)),
            url = "https://example.invalid/another-stream.mkv",
        )
        assertSame(pending.ownership, replacementTitle.ownership)
        ProfileDataContext.invalidate()
        assertFalse(replacementTitle.canOpenPlayer())
    }

    @Test fun aMissingPreparationBindingCannotEnterEitherPlatformPlayer() {
        val legacy = session(null)
        // This compatibility predicate alone is not the platform entry gate.
        assertTrue(legacy.playbackAllowed())
        assertFalse(legacy.canOpenPlayer())
    }

    @Test fun aLogicalServerChangeRetiresAnOnlineSessionWithTheSameProfileId() {
        val pending = session()
        ApiClient.baseUrl = "https://different-synthetic-server.invalid/mediastorm/".toHttpUrl()
        assertFalse(pending.canOpenPlayer())
        cancelled { pending.ownership!!.use { fail("Old server action ran") } }
    }

    @Test fun aVerifiedRouteHandoffRequiresANewOperationEvenForTheSameLogicalOwner() {
        val pending = session()
        ApiClient.configureRoute("http://127.0.0.1:19876/".toHttpUrl(), "synthetic-pairing-credential")
        assertFalse(pending.canOpenPlayer())
        assertTrue(session().canOpenPlayer())
    }

    @Test fun changingOnlyTheTransportCredentialAlsoRetiresTheOldOperation() {
        val pending = session()
        ApiClient.remoteCredential = "synthetic-replacement-credential"
        assertFalse(pending.canOpenPlayer())
    }

    @Test fun localPermissionAndOriginalPreparationAreBothRequired() {
        var localPermission = true
        val owned = session().copy(localFile = true, localFileAccess = { localPermission })
        assertTrue(owned.canOpenPlayer())
        localPermission = false
        assertFalse(owned.canOpenPlayer())
        localPermission = true
        ProfileDataContext.invalidate()
        assertFalse(owned.canOpenPlayer())
        assertFalse(owned.copy(ownership = PlaybackSessionContext.capture(), localFileAccess = null).canOpenPlayer())
    }

    @Test fun delayedOwnerActionDoesNotRunAfterReplacement() = runBlocking {
        val captured = PlaybackSessionContext.capture()
        val resume = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var ownerActions = 0
        val flight = async {
            started.complete(Unit)
            resume.await()
            captured.use { ownerActions++ }
        }
        started.await()
        ProfileDataContext.invalidate()
        resume.complete(Unit)
        try { flight.await(); fail("Delayed stale action completed") }
        catch (_: CancellationException) {}
        assertEquals(0, ownerActions)
    }

    @Test fun ownershipDoesNotExposeADataClassCopyRebindingApi() {
        assertFalse(PlaybackSessionContext::class.java.methods.any { it.name.startsWith("copy") })
    }
}
