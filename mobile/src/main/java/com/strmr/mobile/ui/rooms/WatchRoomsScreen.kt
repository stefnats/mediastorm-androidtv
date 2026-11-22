package com.strmr.mobile.ui.rooms

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.strmr.mobile.ui.shell.PageScaffold
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.Profile
import com.strmr.tv.api.profiles
import com.strmr.tv.library.ProfileDataContext
import com.strmr.tv.player.PlaybackCoordinator
import com.strmr.tv.player.PlaybackRequest
import com.strmr.tv.player.PlaybackSessionContext
import com.strmr.tv.rooms.WatchRoomModel
import kotlinx.coroutines.launch

/** Mobile layout; transport, readiness, state transitions and cleanup live in shared core. */
@Composable
fun WatchRoomsScreen(create: PlaybackRequest? = null, expected: Long = ProfileDataContext.current) {
    val origin = remember(expected) { runCatching { PlaybackSessionContext.capture(expected) to ApiClient.watchRoomGateway(expected) } }
    val pair = origin.getOrNull()
    if (pair == null) { Text("Watch Together is unavailable. Return and select a profile again."); return }
    val owner = pair.first
    var visit by remember(pair) { mutableStateOf(0) }
    val model = remember(pair, visit) { WatchRoomModel(pair.second) }
    val ended by PlaybackCoordinator.sessionsEnded.collectAsState()
    LaunchedEffect(ended) { if (model.hasTransferred) visit++ }
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var profiles by remember(model) { mutableStateOf<List<Profile>>(emptyList()) }
    var selected by remember(model) { mutableStateOf<Set<String>>(emptySet()) }
    var username by remember(model) { mutableStateOf("") }
    var localError by remember(model) { mutableStateOf<String?>(null) }
    DisposableEffect(model) { onDispose { model.close() } }
    LaunchedEffect(model) {
        model.refresh()
        try {
            owner.requireCurrent()
            val rows = ApiClient.profiles()
            owner.requireCurrent()
            profiles = rows.filter { it.id != owner.profileId }
        } catch (e: Exception) { localError = e.message }
    }
    PageScaffold("Watch Together", showBack = true) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(state.notice ?: localError ?: "Each viewer plays their own authorized copy.") }
            item { OutlinedButton(onClick = { model.refresh() }, enabled = !state.busy) { Text("Refresh rooms and invitations") } }
            val room = state.room
            if (room == null) {
                if (create != null) {
                    item { Text("Create: " + create.displayTitle) }
                    items(profiles, key = { "profile:" + it.id }) { profile ->
                        FilterChip(selected = profile.id in selected, enabled = !state.busy,
                            onClick = { selected = if (profile.id in selected) selected - profile.id else selected + profile.id },
                            label = { Text(profile.name) })
                    }
                    item { Button(onClick = { model.create(create, selected.toList()) }, enabled = !state.busy) { Text("Create room") } }
                } else item { Text("Create a room from a movie or selected episode's Watch Together button.") }
                items(state.rooms, key = { "room:" + it.id }) { value ->
                    OutlinedButton(onClick = { model.join(value.id) }, enabled = !state.busy) { Text("Join · " + value.title + " · " + value.creatorName) }
                }
                items(state.invitations.filter { it.status == "pending" }, key = { "invite:" + it.id }) { invite ->
                    Column {
                        Text(invite.title + " · invited by " + invite.creatorName)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { model.accept(invite.id) }, enabled = !state.busy) { Text("Accept with this profile") }
                            OutlinedButton(onClick = { model.decline(invite.id) }, enabled = !state.busy) { Text("Decline") }
                        }
                    }
                }
            } else {
                item { Text(room.title + " · " + room.status) }
                items(room.members, key = { it.profileId }) { Text(it.name + if (it.ready) " · ready" else if (it.joined) " · preparing" else " · invited") }
                item {
                    Button(enabled = !state.busy, onClick = {
                        scope.launch {
                            try {
                                val playback = model.playback(owner)
                                if (!PlaybackCoordinator.playRoom(playback, owner.epoch)) { playback.close().join(); visit++ }
                            }
                            catch (e: Exception) { localError = e.message }
                        }
                    }) { Text("Play my copy in this room") }
                }
                if (model.creator) {
                    item { Button(onClick = { model.startTogether() }, enabled = !state.busy) { Text("Start together when ready") } }
                    item {
                        OutlinedTextField(username, { username = it }, enabled = !state.busy,
                            label = { Text("Friend's account username") })
                    }
                    item { Button(onClick = { model.invite(username) }, enabled = !state.busy && username.isNotBlank()) { Text("Send account invitation") } }
                    items(state.outgoing.filter { it.status == "pending" }, key = { "outgoing:" + it.id }) { invite ->
                        OutlinedButton(onClick = { model.revoke(invite.id) }, enabled = !state.busy) { Text("Revoke · " + invite.inviteeUsername.orEmpty()) }
                    }
                    item { OutlinedButton(onClick = { model.end() }, enabled = !state.busy) { Text("End room for everyone") } }
                }
                item { Text("Back leaves this room. Invitations are sent only when you choose Send.") }
            }
        }
    }
}
