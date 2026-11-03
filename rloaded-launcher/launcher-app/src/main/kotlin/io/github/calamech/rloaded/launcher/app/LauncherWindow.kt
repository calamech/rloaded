package io.github.calamech.rloaded.launcher.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.calamech.rloaded.launcher.LastPlayed
import io.github.calamech.rloaded.launcher.app.resources.Res
import io.github.calamech.rloaded.launcher.app.resources.failure_downloads
import io.github.calamech.rloaded.launcher.app.resources.failure_launch
import io.github.calamech.rloaded.launcher.app.resources.failure_metadata
import io.github.calamech.rloaded.launcher.app.resources.failure_player_name
import io.github.calamech.rloaded.launcher.app.resources.failure_runtime
import io.github.calamech.rloaded.launcher.app.resources.failure_server_address
import io.github.calamech.rloaded.launcher.app.resources.failure_storage
import io.github.calamech.rloaded.launcher.app.resources.play
import io.github.calamech.rloaded.launcher.app.resources.player_name
import io.github.calamech.rloaded.launcher.app.resources.player_name_hint
import io.github.calamech.rloaded.launcher.app.resources.server_address
import io.github.calamech.rloaded.launcher.app.resources.server_address_hint
import io.github.calamech.rloaded.launcher.app.resources.status_installing
import io.github.calamech.rloaded.launcher.app.resources.status_playing
import io.github.calamech.rloaded.launcher.app.resources.status_ready
import io.github.calamech.rloaded.launcher.app.resources.status_stopped
import io.github.calamech.rloaded.launcher.app.resources.update_available
import io.github.calamech.rloaded.launcher.app.resources.update_downloading
import io.github.calamech.rloaded.launcher.app.resources.update_failure_download
import io.github.calamech.rloaded.launcher.app.resources.update_failure_install
import io.github.calamech.rloaded.launcher.app.resources.update_failure_location
import io.github.calamech.rloaded.launcher.app.resources.update_install
import io.github.calamech.rloaded.launcher.app.resources.update_installing
import io.github.calamech.rloaded.launcher.app.resources.update_not_now
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The launcher window: a player name, a server address and a Play button, filled as last played. */
@Suppress("ktlint:standard:function-naming") // Composable functions are named like types.
@Composable
public fun LauncherWindow(
    state: LauncherState,
    updates: UpdateState,
    lastPlayed: LastPlayed,
) {
    val status by state.status.collectAsState()
    val offer by updates.offer.collectAsState()
    var playerName by remember { mutableStateOf(lastPlayed.playerName) }
    var server by remember { mutableStateOf(lastPlayed.server) }
    MaterialTheme {
        Column(Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UpdateBanner(offer, canInstall = state.canPlay, onInstall = updates::accept, onNotNow = updates::decline)
            OutlinedTextField(
                value = playerName,
                onValueChange = { playerName = it },
                label = { Text(stringResource(Res.string.player_name)) },
                supportingText = { Text(stringResource(Res.string.player_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                label = { Text(stringResource(Res.string.server_address)) },
                supportingText = { Text(stringResource(Res.string.server_address_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { state.play(playerName, server) }, enabled = state.canPlay) {
                Text(stringResource(Res.string.play))
            }
            val installing = status as? Status.Installing
            if (installing != null && installing.total > 0) {
                LinearProgressIndicator(progress = { installing.done.toFloat() / installing.total }, modifier = Modifier.fillMaxWidth())
            }
            Text(statusText(status))
        }
    }
}

// Installing quits the launcher, so it waits while the game runs.
@Suppress("ktlint:standard:function-naming") // Composable functions are named like types.
@Composable
private fun UpdateBanner(
    offer: UpdateOffer,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onNotNow: () -> Unit,
) {
    val text =
        when (offer) {
            UpdateOffer.None -> return
            is UpdateOffer.Available -> stringResource(Res.string.update_available, offer.version.toString())
            is UpdateOffer.Downloading -> stringResource(Res.string.update_downloading, offer.version.toString())
            UpdateOffer.Installing -> stringResource(Res.string.update_installing)
            is UpdateOffer.Failed -> stringResource(updateMessage(offer.reason))
        }
    Text(text)
    if (offer is UpdateOffer.Available || offer is UpdateOffer.Failed) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onInstall, enabled = canInstall) { Text(stringResource(Res.string.update_install)) }
            OutlinedButton(onClick = onNotNow) { Text(stringResource(Res.string.update_not_now)) }
        }
    }
}

private fun updateMessage(reason: UpdateFailure): StringResource =
    when (reason) {
        UpdateFailure.DOWNLOAD -> Res.string.update_failure_download
        UpdateFailure.LOCATION -> Res.string.update_failure_location
        UpdateFailure.INSTALL -> Res.string.update_failure_install
    }

@Composable
private fun statusText(status: Status): String =
    when (status) {
        Status.Ready -> stringResource(Res.string.status_ready)
        is Status.Installing -> stringResource(Res.string.status_installing, status.done, status.total)
        Status.Playing -> stringResource(Res.string.status_playing)
        is Status.Stopped -> stringResource(Res.string.status_stopped, status.exitCode)
        is Status.Failed -> stringResource(message(status.reason))
    }

private fun message(reason: FailureReason): StringResource =
    when (reason) {
        FailureReason.PLAYER_NAME -> Res.string.failure_player_name
        FailureReason.SERVER_ADDRESS -> Res.string.failure_server_address
        FailureReason.DOWNLOADS -> Res.string.failure_downloads
        FailureReason.RUNTIME -> Res.string.failure_runtime
        FailureReason.METADATA -> Res.string.failure_metadata
        FailureReason.STORAGE -> Res.string.failure_storage
        FailureReason.LAUNCH -> Res.string.failure_launch
    }
