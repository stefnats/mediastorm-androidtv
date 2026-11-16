package com.strmr.tv.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.CardEpisode
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.api.setWatched
import com.strmr.tv.api.WatchedSelection
import com.strmr.tv.api.vodDenial
import com.strmr.tv.app.AppLog
import com.strmr.tv.home.hideFromContinueWatching
import com.strmr.tv.ui.AppToast
import kotlinx.coroutines.CancellationException

/**
 * The title actions the long-press menus and the details page share (the Expo app's Home
 * actions): Set Aside, Hide from Continue Watching, Hide from this profile / Unhide and Mark
 * as watched / unwatched, each with the Expo app's toast. [changes] is bumped after every
 * one, so Home's playback rows, list grids and details pages refresh. Main thread only.
 */
object TitleActions {
    var changes by mutableIntStateOf(0)
        private set

    /** Runs `action`, then the success toast; a failure shows `failure` and is logged. True on success. */
    private suspend fun run(
        name: String,
        target: DetailsTarget,
        success: String,
        failure: String,
        operation: CustomListOperation? = null,
        action: suspend () -> Unit,
    ): Boolean {
        if (operation != null) return runOwned(operation, success, failure, action)
        return try {
            action()
            changes++
            AppLog.i("TitleActions", "$name ${target.titleId} (${target.name})")
            AppToast.show(success)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("TitleActions", "$name ${target.titleId} failed: ${e.message}")
            AppToast.show(failure)
            false
        }
    }

    /** Actual owned-action publication adapter; legacy non-list actions keep their existing paths. */
    internal suspend fun runOwned(
        operation: CustomListOperation,
        success: String,
        failure: String,
        action: suspend () -> Unit,
    ): Boolean = try {
        operation.check()
        action()
        operation.publish { changes++; AppToast.show(success) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        operation.publish { AppToast.show(failure) }
        false
    }

    suspend fun setAside(target: DetailsTarget, operation: CustomListOperation) =
        run("set-aside", target, "Added to Set Aside", "Failed to add to Set Aside", operation) {
            SetAsideStore.add(target, operation)
        }

    suspend fun removeFromSetAside(target: DetailsTarget, operation: CustomListOperation) =
        run("remove-set-aside", target, "Removed from Set Aside", "Failed to remove from Set Aside", operation) {
            SetAsideStore.remove(target, operation)
        }

    suspend fun hideFromContinueWatching(target: DetailsTarget, operation: CustomListOperation) =
        run("hide-continue-watching", target, "Removed from Continue Watching", "Failed to remove from Continue Watching", operation) {
            operation.requireTarget(target)
            checkedListStage(operation.owner, operation) { ApiClient.hideFromContinueWatching(target.titleId, operation.owner.epoch) }
        }

    suspend fun hide(target: DetailsTarget, operation: CustomListOperation) =
        run("hide", target, "Hidden from this profile", "Failed to hide item", operation) { HiddenItemsStore.hide(target, operation) }

    suspend fun unhide(target: DetailsTarget, operation: CustomListOperation) =
        run("unhide", target, "Unhidden", "Failed to unhide item", operation) { HiddenItemsStore.unhide(target, operation) }

    suspend fun setWatched(selection: WatchedSelection): Boolean = try {
        selection.check()
        ApiClient.setWatched(selection)
        selection.publish { changes++; AppToast.show(if (selection.watched) "Marked as watched" else "Marked as unwatched") }
    } catch (error: CancellationException) { throw error
    } catch (error: Exception) {
        selection.publish { AppToast.show(error.vodDenial?.message ?: "Failed to update watched state") }
        false
    }

    /** Only a current original mutation may publish a change notification. */
    fun changed(operation: CustomListOperation) { operation.publish { changes++ } }

}
