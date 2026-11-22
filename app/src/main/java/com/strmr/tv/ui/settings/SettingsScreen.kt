package com.strmr.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.strmr.tv.ui.details.DetailsInfo
import com.strmr.tv.api.ApiClient
import com.strmr.tv.api.checkHealth
import com.strmr.tv.api.playbackSettings
import com.strmr.tv.api.kidsAgeProfile
import com.strmr.tv.app.AppSession
import com.strmr.tv.app.Config
import com.strmr.tv.auth.ServerAddress
import com.strmr.tv.diagnostics.LogUploader
import com.strmr.tv.remote.IrohRoute
import com.strmr.tv.ui.LocalNavigator
import com.strmr.tv.ui.Page
import com.strmr.tv.ui.PageMargin
import com.strmr.tv.ui.PickerDialog
import com.strmr.tv.ui.clipVertically
import com.strmr.tv.ui.onboarding.BrandMark
import com.strmr.tv.ui.onboarding.ProfileAvatar
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

/** Server, profile and account, and About. The rest of Settings is Tier 2. */
@Composable
fun SettingsScreen() {
    val session = AppSession
    val server by session.serverUrl.collectAsState()
    val remote by session.isRemote.collectAsState()
    val profile by session.profile.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val settings = ApiClient.playbackSettings()
        TrailerSettings.serverValue = settings.autoPlayTrailers
        CreditsDetectionSettings.serverValue = settings.creditsDetection
        MatchFrameRateSettings.serverValue = settings.matchFrameRate
    }
    LaunchedEffect(Unit) { loadTrackDefaults() }
    LaunchedEffect(Unit) { SpoilerSettings.refresh() }
    LaunchedEffect(Unit) { runCatching { DetailsInfo.refresh() } }
    LaunchedEffect(Unit) { BadgeSettings.refresh() }

    var health by remember { mutableStateOf<String?>(null) }
    fun checkConnection() {
        health = null
        scope.launch {
            health = try { ApiClient.checkHealth(ApiClient.baseUrl); "Connected" } catch (e: Exception) { e.message ?: "Unreachable" }
        }
    }
    LaunchedEffect(Unit) { checkConnection() }

    // The tvOS layout: the brand and profile on the left, the settings list on the right.
    Row(Modifier.fillMaxSize().padding(top = 76.dp, start = PageMargin, end = PageMargin)) {
        Column(
            Modifier.weight(0.4f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BrandMark(90.dp)
            Text(profile?.name ?: "", fontSize = 20.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 16.dp))
        }
        SettingsList(server, remote, health, ::checkConnection, Modifier.weight(0.6f))
    }
}

@Composable
private fun SettingsList(server: HttpUrl?, remote: Boolean, health: String?, checkConnection: () -> Unit, modifier: Modifier) {
    val session = AppSession
    val tasteSetup = com.strmr.tv.ui.rememberTasteSetupAction()
    val profile by session.profile.collectAsState()
    val version by session.serverVersion.collectAsState()
    val scope = rememberCoroutineScope()
    val navigator = LocalNavigator.current
    val ageContext = com.strmr.tv.library.ProfileDataContext.current
    var ageRefresh by remember(profile?.id, server, ageContext) { mutableStateOf(0) }
    var ageSummary by remember(profile?.id, server, ageContext) { mutableStateOf<com.strmr.tv.api.KidsAgeSummary?>(null) }
    var ageLoading by remember(profile?.id, server, ageContext) { mutableStateOf(true) }
    var ageError by remember(profile?.id, server, ageContext) { mutableStateOf<String?>(null) }
    var showingAgeRules by remember(profile?.id, server, ageContext) { mutableStateOf(false) }
    val ageEditor = com.strmr.tv.api.kidsAgeEditorUrl(server, profile?.id.orEmpty(), remote)
    val ageAndroidContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(profile?.id, server, ageContext, ageRefresh) {
        ageSummary = null
        ageError = null
        ageLoading = true
        val selected = profile?.id
        if (selected == null) { ageLoading = false; return@LaunchedEffect }
        try {
            val current = com.strmr.tv.api.ApiClient.kidsAgeProfile(selected, ageContext)
            com.strmr.tv.library.ProfileDataContext.requireCurrent(ageContext)
            ageSummary = com.strmr.tv.api.kidsAgeSummary(current)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            if (com.strmr.tv.library.ProfileDataContext.current == ageContext) ageError = "Current rules could not be read. Refresh or open the server Web UI."
        } finally {
            if (com.strmr.tv.library.ProfileDataContext.current == ageContext) ageLoading = false
        }
    }
    val ageMessage = (if (ageLoading) "Loading current profile rules…" else ageError ?: ageSummary?.let { it.headline + "\n\n" + it.detail } ?: "Rule unavailable.") +
        "\n\nChanges require the existing parent confirmation in the server Web UI. Sign in there separately, then return and select Refresh." +
        if (ageEditor != null) "\n\n$ageEditor" else "\n\nOpen the server Web UI on a device connected to the server. The app's remote link cannot open this page in a browser."
    if (showingAgeRules) {
        com.strmr.tv.ui.ChoiceDialog("Child age rules", ageMessage,
            choices = buildList {
                if (!ageLoading) add(com.strmr.tv.ui.DialogChoice("Refresh") { ageRefresh++ })
                if (ageEditor != null) add(com.strmr.tv.ui.DialogChoice("Edit in server Web UI") {
                    runCatching { ageAndroidContext.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ageEditor.toString()))) }
                        .onFailure { ageError = "No browser is available here. Open the address below on your phone or computer." }
                })
                add(com.strmr.tv.ui.DialogChoice("Close") { showingAgeRules = false })
            }, onDismiss = { showingAgeRules = false }, dismissOnChoice = false)
    }
    var route by remember { mutableStateOf(IrohRoute.Detecting) }
    LaunchedEffect(remote) {
        if (!remote) return@LaunchedEffect
        while (true) {
            route = IrohRoute.current()
            delay(IrohRoute.POLL_MS)
        }
    }
    var startupReport by remember { mutableStateOf<com.strmr.tv.player.PlaybackStartupReport?>(null) }
    startupReport?.let { report ->
        com.strmr.tv.ui.ChoiceDialog("Playback Startup Timings", report.text(),
            choices = listOf(
                com.strmr.tv.ui.DialogChoice("Refresh") { startupReport = com.strmr.tv.player.PlaybackStartupDiagnostics.snapshot() },
                com.strmr.tv.ui.DialogChoice("Close") { startupReport = null },
            ), onDismiss = { startupReport = null }, dismissOnChoice = false)
    }
    var pickingBackground by remember { mutableStateOf(false) }
    if (pickingBackground) {
        val options = AppearanceSettings.Background.entries
        PickerDialog("Background", options.map { it.label }, AppearanceSettings.background.ordinal,
                     onPick = { AppearanceSettings.choose(options[it]) }, onDismiss = { pickingBackground = false })
    }
    var pickingLogoScale by remember { mutableStateOf(false) }
    if (pickingLogoScale) {
        val options = LogoSettings.Scale.entries
        PickerDialog("Home Title Logo Size", options.map { it.label }, LogoSettings.scale.ordinal,
                     onPick = { LogoSettings.set(options[it]) }, onDismiss = { pickingLogoScale = false })
    }
    var pickingDetailsLogoScale by remember { mutableStateOf(false) }
    if (pickingDetailsLogoScale) {
        val options = LogoSettings.Scale.entries
        PickerDialog("Details Title Logo Size", options.map { it.label }, LogoSettings.detailsScale.ordinal,
                     onPick = { LogoSettings.setDetails(options[it]) }, onDismiss = { pickingDetailsLogoScale = false })
    }
    LazyColumn(
        // Clipped below the tab bar, so rows never scroll under it.
        modifier.fillMaxHeight().clipVertically(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Header("Server") }
        item {
            ListItem(
                selected = false, onClick = {},
                headlineContent = { Text("Address") },
                trailingContent = {
                    Text(if (remote) "Remote access (iroh)" else server?.let { ServerAddress.display(it) } ?: "Not connected")
                },
            )
        }
        if (remote) {
            item {
                ListItem(
                    selected = false, onClick = {},
                    headlineContent = { Text("Route") },
                    supportingContent = { Text("Direct is a peer-to-peer link; Relay goes through an iroh relay server") },
                    trailingContent = { Text(route.label) },
                )
            }
        }
        item {
            ListItem(
                selected = false, onClick = checkConnection,
                headlineContent = { Text("Status") },
                supportingContent = { Text("Select to check the connection") },
                trailingContent = { Text(health ?: "Checking…") },
            )
        }
        speedTestRows()
        item {
            ListItem(
                selected = false, onClick = { session.changeServer() },
                headlineContent = { Text("Change Server…") },
            )
        }
        item { Header("Profile") }
        item {
            ListItem(
                selected = false, onClick = { scope.launch { session.switchProfile() } },
                headlineContent = { Text(profile?.name ?: "") },
                supportingContent = { Text("Switch Profile") },
                leadingContent = { profile?.let { ProfileAvatar(it, 32.dp) } },
            )
        }
        item {
            ListItem(selected = false, onClick = tasteSetup,
                headlineContent = { Text("Starting interests") },
                supportingContent = { Text("Choose or edit the favorites used for this profile's suggestions") })
        }
        if (profile != null) item {
            ListItem(selected = false, onClick = { showingAgeRules = true },
                headlineContent = { Text("Child age rules") },
                supportingContent = { Text(if (ageLoading) "Loading current rules…" else ageError ?: ageSummary?.headline ?: "Unavailable") })
        }
        item {
            ListItem(selected = false, onClick = { navigator.open(Page.HiddenItems) },
                headlineContent = { Text("Hidden Items") },
                supportingContent = { Text("Titles hidden from Continue Watching or from this profile") })
        }
        item { Header("Home") }
        item {
            ListItem(
                selected = false, onClick = { navigator.open(Page.HomeLayout) },
                headlineContent = { Text("Home Layout") },
                supportingContent = { Text("Top carousel, focused title info, view switcher, card size and position, each row's style") },
            )
        }
        item {
            ListItem(
                selected = false, onClick = { navigator.open(Page.TopMenu) },
                headlineContent = { Text("Menu") },
                supportingContent = { Text("Top bar or side menu, and which entries show") },
            )
        }
        item {
            ListItem(
                selected = false, onClick = { pickingLogoScale = true },
                headlineContent = { Text("Home Title Logo Size") },
                supportingContent = { Text("Titles' logos in Home's hero and carousel") },
                trailingContent = { Text(LogoSettings.scale.label) },
            )
        }
        item {
            ListItem(
                selected = false, onClick = { pickingDetailsLogoScale = true },
                headlineContent = { Text("Details Title Logo Size") },
                supportingContent = { Text("Titles' logos on details pages") },
                trailingContent = { Text(LogoSettings.detailsScale.label) },
            )
        }
        item { Header("Appearance") }
        item {
            ListItem(
                selected = false, onClick = { pickingBackground = true },
                headlineContent = { Text("Background") },
                supportingContent = { Text("Behind every page but Home") },
                trailingContent = { Text(AppearanceSettings.background.label) },
            )
        }
        item {
            val enabled = ClockSettings.enabled
            ListItem(
                selected = false,
                onClick = { ClockSettings.set(!enabled) },
                headlineContent = { Text("Show Clock") },
                supportingContent = { Text("The time at the top of Home and in the player") },
                trailingContent = { Text(if (enabled) "On" else "Off") },
            )
        }
        item { Header("Card Badges") }
        badgeSettingRows()
        item { Header("Spoilers") }
        spoilerSettingRows()
        item { Header("Details Page") }
        detailsInfoSettingRows()
        item { Header("Playback") }
        item {
            val editor = rememberEpisodeAutoplayEditor()
            val state by editor.state.collectAsState()
            ListItem(
                selected = false,
                enabled = !state.loading && !state.saving,
                onClick = { scope.launch { state.enabled?.let { editor.save(!it) } ?: editor.load() } },
                headlineContent = { Text("Auto-play Next Episode") },
                supportingContent = { Text(state.description) },
            )
        }
        item {
            ListItem(
                selected = false, onClick = { navigator.open(Page.SubtitleStyle) },
                headlineContent = { Text("Subtitle Style") },
                supportingContent = { Text("Size, colour, edge, background and position") },
            )
        }
        item {
            val override = TrailerSettings.override
            val server = TrailerSettings.serverValue
            ListItem(
                selected = false,
                // Default (follows the server) → On → Off → Default.
                onClick = { TrailerSettings.set(when (override) { null -> true; true -> false; false -> null }) },
                headlineContent = { Text("Auto-play Trailers") },
                supportingContent = {
                    Text(when (override) {
                        null -> "Default" + (server?.let { if (it) " (On)" else " (Off)" } ?: "")
                        true -> "On"
                        false -> "Off"
                    })
                },
            )
        }
        item {
            val override = CreditsDetectionSettings.override
            val server = CreditsDetectionSettings.serverValue
            ListItem(
                selected = false,
                // Default (follows the server) → On → Off → Default.
                onClick = { CreditsDetectionSettings.set(when (override) { null -> true; true -> false; false -> null }) },
                headlineContent = { Text("Credits Detection") },
                supportingContent = {
                    Text(when (override) {
                        null -> "Default" + (server?.let { if (it) " (On)" else " (Off)" } ?: "")
                        true -> "On"
                        false -> "Off"
                    } + " · finds unknown end credits on screen for Next Episode")
                },
            )
        }
        item {
            val override = MatchFrameRateSettings.override
            val server = MatchFrameRateSettings.serverValue
            ListItem(
                selected = false,
                // Default (follows the server) → On → Off → Default.
                onClick = { MatchFrameRateSettings.set(when (override) { null -> true; true -> false; false -> null }) },
                headlineContent = { Text("Match Frame Rate") },
                supportingContent = {
                    Text(when (override) {
                        null -> "Default" + (server?.let { if (it) " (On)" else " (Off)" } ?: "")
                        true -> "On"
                        false -> "Off"
                    } + " · switches the TV's refresh rate to the video's")
                },
            )
        }
        item {
            val enabled = AutoSwitchSettings.enabled
            ListItem(
                selected = false,
                onClick = { AutoSwitchSettings.set(!enabled) },
                headlineContent = { Text("Auto Switch Sources") },
                supportingContent = { Text("Move to another source when the current one fails or keeps stalling") },
                trailingContent = { Text(if (enabled) "On" else "Off") },
            )
        }
        trackSettingRows()
        item { PlaybackQualityRows() }
        item {
            val enabled = DolbyVisionSettings.profile7Enabled
            ListItem(
                selected = false,
                onClick = { DolbyVisionSettings.setProfile7(!enabled) },
                headlineContent = { Text("Dolby Vision Profile 7") },
                supportingContent = { Text("Convert UHD Blu-ray Dolby Vision so this device can play it") },
                trailingContent = { Text(if (enabled) "On" else "Off") },
            )
        }
        liveHlsResetRow()
        item { Header("Updates") }
        updateRows()
        item { Header("Account") }
        item {
            ListItem(
                selected = false, onClick = { scope.launch { session.signOut() } },
                headlineContent = { Text("Sign Out") },
                supportingContent = { Text(session.username ?: "") },
            )
        }
        item { Header("About") }
        item {
            ListItem(
                selected = false, onClick = {},
                headlineContent = { Text("MediaStorm for Android TV") },
                supportingContent = {
                    Text("App ${Config.fullVersion}" + (version?.let { " · Server ${it.version}" } ?: ""))
                },
            )
        }
        item {
            ListItem(selected = false,
                onClick = { startupReport = com.strmr.tv.player.PlaybackStartupDiagnostics.snapshot() },
                headlineContent = { Text("Playback Startup Timings") },
                supportingContent = { Text("Local click-to-first-frame observations and completed outcomes") })
        }
        item {
            val status = LogUploader.status
            ListItem(
                selected = false, onClick = { LogUploader.uploadNow() },
                headlineContent = { Text("Upload Logs") },
                supportingContent = {
                    Text(when (status) {
                        LogUploader.Status.Idle -> "Send this device's log to the server for the admin UI's Logs page"
                        LogUploader.Status.Uploading -> "Uploading…"
                        is LogUploader.Status.Uploaded -> "Uploaded at " + status.at.atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
                        is LogUploader.Status.Failed -> "Upload failed: ${status.message}"
                    })
                },
            )
        }
    }
}

@Composable
private fun Header(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = Color.White.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp, start = 4.dp),
    )
}
