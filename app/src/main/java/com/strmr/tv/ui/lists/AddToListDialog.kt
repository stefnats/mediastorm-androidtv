package com.strmr.tv.ui.lists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.home.CustomList
import com.strmr.tv.home.WatchlistItem
import com.strmr.tv.library.CustomListsStore
import com.strmr.tv.library.CustomListOperations
import com.strmr.tv.library.CustomListOperation
import com.strmr.tv.library.CustomListSelectionKey
import com.strmr.tv.library.ProfileDataContext
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CancellationException
import com.strmr.tv.ui.SidePanelDialog
import com.strmr.tv.ui.onboarding.FormField
import kotlinx.coroutines.launch

/**
 * "Manage Lists…" as a side panel, like Watch State and the Live category filter: each of the
 * profile's lists, checked while it holds the title. Selecting a list adds or removes the
 * title in place with the panel left up; "New List…" creates one with the title in it. Shown
 * over everything while [CustomListsStore.picking] is set.
 */
@Composable
fun AddToListDialog(target: DetailsTarget) {
    val scope = rememberCoroutineScope()
    val expected = CustomListsStore.pickingOwner(target)?.epoch ?: Long.MIN_VALUE
    val operations = remember(expected, CustomListSelectionKey(target)) {
        CustomListOperations(expected, target) { CustomListsStore.picking === target }
    }
    DisposableEffect(operations) { onDispose { operations.close() } }
    var memberships by remember(operations) { mutableStateOf<Map<String, WatchlistItem>?>(null) }
    var busy by remember(operations) { mutableStateOf(emptySet<String>()) }
    var error by remember(operations) { mutableStateOf<String?>(null) }
    var naming by remember(operations) { mutableStateOf(false) }
    val close = {
        if (operations.isCurrent) {
            CustomListsStore.clearPicking(target, expected)
            operations.close()
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(operations) {
        val operation = operations.begin(key = "load") ?: return@LaunchedEffect
        try {
            operations.run(operation) {
                CustomListsStore.refresh(expected, it)
                val loaded = CustomListsStore.memberships(target, expected, it)
                it.publish { memberships = loaded }
                withFrameNanos { }
                it.check()
                runCatching { first.requestFocus() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { operation.publish { error = "Unable to load lists. Try again." } }
    }
    fun toggle(list: CustomList) {
        if (list.id in busy || memberships == null || !operations.isCurrent) return
        val stored = memberships?.get(list.id)
        operations.retire("load")
        val operation = operations.begin(list.id, stored) ?: return
        busy = busy + list.id
        scope.launch {
            try {
                operations.run(operation) {
                    if (stored != null) CustomListsStore.remove(stored, list.id, operation = it, expectedContext = expected)
                    else CustomListsStore.add(target, list.id, it, expected)
                    val updated = CustomListsStore.memberships(target, expected, it)[list.id]
                    it.publish {
                        memberships = memberships.orEmpty().toMutableMap().apply {
                            if (updated == null) remove(list.id) else put(list.id, updated)
                        }
                        error = null
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { operation.publish { error = "Unable to update this list. Try again." } }
            finally { operation.publish { busy = busy - list.id } }
        }
    }

    SidePanelDialog("Manage Lists", target.name, close) {
        error?.let { item { Text(it, fontSize = 14.sp, color = Color(0xFFFF6B6B)) } }
        val loaded = memberships
        if (loaded == null) {
            item { CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp), color = Color.White, strokeWidth = 2.dp) }
        } else {
            items(CustomListsStore.userLists, key = { it.id }) { list ->
                val member = loaded[list.id] != null
                ListItem(
                    selected = member, onClick = { toggle(list) },
                    modifier = if (list == CustomListsStore.userLists.first()) Modifier.focusRequester(first) else Modifier,
                    headlineContent = { Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = list.itemCount?.let { { Text(if (it == 1) "1 title" else "$it titles") } },
                    trailingContent = when {
                        list.id in busy -> { { CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) } }
                        member -> { { Icon(Icons.Filled.Check, contentDescription = "In list") } }
                        else -> null
                    },
                )
            }
        }
        item {
            ListItem(
                selected = false, onClick = { if (operations.isCurrent) naming = true },
                modifier = if (loaded != null && CustomListsStore.userLists.isEmpty()) Modifier.focusRequester(first) else Modifier,
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(20.dp)) },
                headlineContent = { Text("New List…") },
            )
        }
    }

    if (naming) {
        ListNameDialog(
            title = "New List", message = "The new list will start with ${target.name}.",
            confirm = "Create and Add", onDismiss = { naming = false },
            expectedContext = expected, target = target, selected = { operations.isCurrent },
        ) { name, operation ->
            operations.retire("load")
            val initial = memberships == null
            val list = CustomListsStore.createAndAdd(name, target, operation)
            val updated = CustomListsStore.memberships(target, expected, operation)
            operation.publish {
                memberships = if (initial) updated else memberships.orEmpty().toMutableMap().apply {
                    updated[list.id]?.let { put(list.id, it) }
                }
            }
        }
    }
}

/**
 * A list name prompt (new or rename). The name is checked against the profile's lists
 * before [onSubmit] runs; its failure is shown in place and keeps the dialog open.
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
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
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
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(460.dp).background(Color(0xFF1C1C1E), RoundedCornerShape(20.dp)).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                message?.let { Text(it, color = Color.White.copy(alpha = 0.7f)) }
                FormField(name, { name = it; problem = null }, "Name", imeAction = ImeAction.Done, onSubmit = ::submit, modifier = Modifier.focusRequester(field))
                problem?.let { Text(it, fontSize = 14.sp, color = Color(0xFFFF6B6B)) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    OutlinedButton(onClick = dismiss) { Text("Cancel") }
                    Button(onClick = ::submit) { Text(if (working) "Saving…" else confirm) }
                }
            }
        }
    }
}
