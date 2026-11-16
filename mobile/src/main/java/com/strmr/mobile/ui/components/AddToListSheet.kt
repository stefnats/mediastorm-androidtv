package com.strmr.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.strmr.tv.api.DetailsTarget
import com.strmr.tv.home.CustomList
import com.strmr.tv.home.WatchlistItem
import com.strmr.mobile.ui.lists.ListNameDialog
import com.strmr.tv.library.CustomListOperations
import com.strmr.tv.library.CustomListSelectionKey
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CancellationException
import com.strmr.tv.library.CustomListsStore
import kotlinx.coroutines.launch

/**
 * Add to List… (the iPhone app's Manage Lists sheet): each of the profile's lists, checked
 * where it holds the title. Tapping a list adds or removes the title in place; New List…
 * creates one with the title in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToListSheet(target: DetailsTarget, onDismiss: () -> Unit) {
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
            onDismiss()
        }
    }
    LaunchedEffect(operations) {
        val operation = operations.begin(key = "load") ?: return@LaunchedEffect
        try {
            operations.run(operation) {
                CustomListsStore.refresh(expected, it)
                val loaded = CustomListsStore.memberships(target, expected, it)
                it.publish { memberships = loaded }
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

    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.navigationBarsPadding()) {
            SheetHeader("Add to List", target.name, null)
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                     modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                val lists = CustomListsStore.userLists
                if (memberships == null) {
                    item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                } else {
                    items(lists, key = { it.id }) { list ->
                        val member = memberships?.containsKey(list.id) == true
                        ListItem(
                            headlineContent = { Text(list.name) },
                            leadingContent = {
                                Icon(
                                    if (member) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                    contentDescription = if (member) "In list" else "Not in list",
                                    tint = if (member) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                if (list.id in busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else list.itemCount?.let { Text(it.toString()) }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = list.id !in busy) { toggle(list) },
                        )
                    }
                }
                item {
                    ListItem(
                        headlineContent = { Text("New List…") },
                        leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { if (operations.isCurrent) naming = true },
                    )
                }
            }
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
