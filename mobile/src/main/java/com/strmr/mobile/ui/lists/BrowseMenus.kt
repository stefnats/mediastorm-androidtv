package com.strmr.mobile.ui.lists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.strmr.mobile.ui.library.rememberLibraryOpener
import com.strmr.mobile.ui.shell.LocalNavigator
import com.strmr.tv.home.BrowseList
import com.strmr.tv.home.HomeLink
import com.strmr.tv.home.offersRandom
import com.strmr.tv.home.sorts
import com.strmr.tv.library.CustomListsStore
import com.strmr.tv.library.CustomListOperations
import com.strmr.tv.library.CustomListOperation
import com.strmr.tv.library.CustomListSelectionKey
import com.strmr.tv.library.ProfileDataContext
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CancellationException
import com.strmr.tv.ui.lists.ListGridModel
import kotlinx.coroutines.launch

/** The list page's top bar actions: Random, the sort menu, and Rename / Delete on the profile's own lists. */
@Composable
fun BrowseActions(model: ListGridModel) {
    val navigator = LocalNavigator.current
    val openLibrary = rememberLibraryOpener()
    val scope = rememberCoroutineScope()
    val originalSection = remember(model) { navigator.section }
    val originalPage = remember(model) { navigator.current.lastOrNull() }
    val selected = { navigator.section == originalSection && navigator.current.lastOrNull() === originalPage && model.isCurrent }
    val active = selected()
    val operations = remember(model, active) { CustomListOperations(model.dataContext, selected = selected) }
    DisposableEffect(operations) { onDispose { operations.close() } }
    var savedFeedMenu by remember(model) { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var managing by remember(operations) { mutableStateOf(false) }
    var renaming by remember(operations) { mutableStateOf(false) }
    var deleting by remember(operations) { mutableStateOf(false) }
    val list = model.list

    if (list == BrowseList.ContinueWatching || model.canOpenSavedRecent || model.canOpenSavedPopular || model.canOpenSavedSimilar) Box {
        IconButton(onClick = { if (selected()) savedFeedMenu = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = savedFeedMenu, onDismissRequest = { savedFeedMenu = false }) {
            DropdownMenuItem(text = { Text(if (model.canOpenSavedRecent) "Browse saved Recently Watched" else if (model.canOpenSavedPopular) "Browse saved Popular on This Server" else if (model.canOpenSavedSimilar) "Browse saved recommendations" else "Browse saved Continue Watching") }, onClick = {
                val producer = requireNotNull(scope.coroutineContext[kotlinx.coroutines.Job])
                val accepted = if (model.canOpenSavedRecent) model.openSavedRecent(producer, selected) else if (model.canOpenSavedPopular) model.openSavedPopular(producer, selected) else if (model.canOpenSavedSimilar) model.openSavedSimilar(producer, selected) else model.openSavedWatching(producer, selected)
                savedFeedMenu = false
                if (accepted != null) com.strmr.tv.api.ApiClient.inContext(model.dataContext) { navigator.openList(accepted) }
            })
        }
    }
    if (list.offersRandom) {
        IconButton(onClick = {
            model.pickRandom { link ->
                when (link) {
                    is HomeLink.Details -> navigator.openDetails(link.target)
                    is HomeLink.Library -> openLibrary(link.entry)
                    else -> Unit
                }
            }
        }) {
            if (model.picking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Casino, "Random")
        }
    }
    if (list.sorts.isNotEmpty() && (list != BrowseList.Hidden && list != BrowseList.LovedStored && list !is BrowseList.CollectionCached && list !is BrowseList.ContinueWatchingCached && list !is BrowseList.RecentlyWatchedCached && list !is BrowseList.PopularOnServerCached && list !is BrowseList.SimilarCached || model.originalLanguageFilterSupported)) {
        Box {
            IconButton(onClick = { sorting = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
            DropdownMenu(expanded = sorting, onDismissRequest = { sorting = false }) {
                SortMenuItems(list, model.options) { sort ->
                    sorting = false
                    model.options = model.options.choose(sort)
                }
            }
        }
    }
    if (model.manageable) {
        Box {
            IconButton(onClick = { if (operations.isCurrent) managing = true }) { Icon(Icons.Filled.MoreVert, "List Actions") }
            DropdownMenu(expanded = managing, onDismissRequest = { managing = false }) {
                DropdownMenuItem(
                    text = { Text("Rename…") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                    onClick = { if (operations.isCurrent) { managing = false; renaming = true } },
                )
                DropdownMenuItem(
                    text = { Text("Delete List…") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { if (operations.isCurrent) { managing = false; deleting = true } },
                )
            }
        }
    }

    val customId = model.customId
    if (renaming && customId != null) {
        ListNameDialog("Rename List", confirm = "Rename", initial = model.title, renaming = customId,
            expectedContext = model.dataContext, selected = { operations.isCurrent }, onDismiss = { renaming = false }) { name, operation ->
            if (name != model.title) CustomListsStore.rename(customId, name, operation)
        }
    }
    val deletion = remember(deleting, operations, customId) { if (deleting && customId != null) operations.begin(customId, key = "delete") else null }
    if (deleting && customId != null && deletion != null) {
        val dismiss = { if (deletion.publish { deleting = false }) operations.retire("delete") }
        AlertDialog(
            onDismissRequest = dismiss, title = { Text("Delete “${model.title}”?") },
            text = { Text("The titles stay in your library; only the list goes.") },
            confirmButton = {
                TextButton(onClick = {
                    if (deletion.publish { deleting = false }) scope.launch {
                        try {
                            operations.run(deletion) { CustomListsStore.delete(customId, it); it.publish { navigator.pop() } }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { deletion.publish { model.actionError = "Unable to delete this list. Try again." } }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
    }
}

/**
 * A list name prompt (new or rename). The name is checked against the profile's lists before
 * [onSubmit] runs; a failure shows under the field and keeps the dialog open.
 */
@Composable
fun ListNameDialog(
    title: String,
    message: String? = null,
    confirm: String,
    initial: String = "",
    renaming: String? = null,
    onDismiss: () -> Unit,
    expectedContext: Long,
    target: com.strmr.tv.api.DetailsTarget? = null,
    selected: () -> Boolean = { true },
    onSubmit: suspend (String, CustomListOperation) -> Unit,
) {
    val operations = remember(expectedContext, renaming, CustomListSelectionKey(target)) { CustomListOperations(expectedContext, target, selected) }
    DisposableEffect(operations) { onDispose { operations.close() } }
    var name by remember(operations) { mutableStateOf(initial) }
    var problem by remember(operations) { mutableStateOf<String?>(null) }
    var working by remember(operations) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dismiss = { if (operations.isCurrent) { operations.close(); onDismiss() } }
    fun submit() {
        if (working || !operations.isCurrent) return
        CustomListsStore.nameProblem(name, renaming)?.let { problem = it; return }
        val submitted = name.trim()
        val operation = operations.begin(listId = renaming, key = "submit") ?: return
        working = true
        scope.launch {
            try {
                operations.run(operation) { onSubmit(submitted, it); it.publish(onDismiss) }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { operation.publish { problem = "Unable to save this list. Try again." }
            } finally { operation.publish { working = false } }
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column {
                message?.let { Text(it) }
                OutlinedTextField(
                    name, { name = it; problem = null }, label = { Text("Name") }, singleLine = true,
                    isError = problem != null, supportingText = problem?.let { { Text(it) } },
                )
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = !working) { Text(if (working) "Saving…" else confirm) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}
