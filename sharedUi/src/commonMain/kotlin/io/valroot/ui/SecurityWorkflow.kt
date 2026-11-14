package io.valroot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.valroot.core.application.PortfolioApplication

/**
 * Messages that outlive the product screens: a restored backup replaces the application and so rebuilds the screens,
 * and its result is shown by the new ones.
 */
internal val LocalLastingNotices =
    androidx.compose.runtime.staticCompositionLocalOf<kotlinx.coroutines.channels.Channel<String>?> { null }

/**
 * Blocks the screen while a backup is saved or restored, so a long restore does not look like a failure. It sits outside
 * the screens a restore rebuilds, so it stays up until the restore ends.
 */
@Composable
internal fun BackupWorkDialog(host: PlatformActions) {
    val work by host.backupWork.collectAsState()
    val message = work ?: return
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.testTag("backup-work"),
        ) {
            Row(
                Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(message)
                    Text("Keep Valroot open until it finishes.", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
internal fun ColumnScope.SecurityWorkflow(
    type: String,
    host: PlatformActions,
    passwordState: MutableState<String>,
    confirmationState: MutableState<String>,
    acceptedBackupState: MutableState<Boolean>,
    errorState: MutableState<String?>,
    notify: (String) -> Unit,
    close: () -> Unit,
) {
    val lasting = LocalLastingNotices.current
    val openedBackup by host.openedBackup.collectAsState()
    var password by passwordState
    var confirmation by confirmationState
    var acceptedBackup by acceptedBackupState
    var error by errorState

    Text(
        when (type) {
            "restore" ->
                (openedBackup?.let { "Restore $it. " } ?: "Choose an encrypted Valroot backup. ") +
                    "After authentication and schema validation, it replaces this device’s portfolio. " +
                    "Save a backup of your current data first."
            "lock" ->
                "This password hides the app screen. It does not encrypt the local database. " +
                    "Use device or disk encryption for storage protection."
            else ->
                "Your portable backup is encrypted and authenticated. " +
                    "Keep the password somewhere safe; it cannot be recovered."
        },
        fontSize = 13.sp,
    )
    SecretField("Password (at least 8 characters)", password) { password = it }
    if (type != "restore") SecretField("Confirm password", confirmation) { confirmation = it }
    if (type ==
        "restore"
    ) {
        Row(Modifier.toggleable(value = acceptedBackup, role = Role.Checkbox) { acceptedBackup = it }) {
            Checkbox(checked = acceptedBackup, onCheckedChange = null)
            Text(
                "Replace the current local portfolio after validating the backup",
                Modifier.weight(1f).padding(top = 8.dp),
                fontSize = 13.sp,
            )
        }
    }
    Button(onClick = {
        if (password.length < 8 ||
            (type != "restore" && password != confirmation)
        ) {
            error = "Use at least 8 characters and matching passwords."
            return@Button
        }
        when (type) {
            "lock" ->
                host.setLockPassword(password) {
                    notify(it)
                    close()
                }
            "restore" -> {
                if (!acceptedBackup) {
                    error = "Confirm that this backup will replace the local portfolio."
                    return@Button
                }
                host.restoreBackup(password) { message -> lasting?.trySend(message) ?: notify(message) }
                close()
            }
            else -> {
                host.saveBackup(password) { notify(it) }
                close()
            }
        }
    }, modifier = Modifier.fillMaxWidth()) {
        Text(
            when (type) {
                "restore" -> "Choose backup & restore"
                "lock" -> "Enable privacy lock"
                else -> "Save encrypted backup"
            },
        )
    }
}

@Composable
internal fun ColumnScope.WipeWorkflow(
    app: PortfolioApplication,
    host: PlatformActions,
    nameState: MutableState<String>,
    execute: (String, () -> Unit) -> Unit,
    close: () -> Unit,
) {
    var name by nameState

    Text(
        "This removes owners, accounts, activity, prices, corrections and settings from this device. Export a backup first. Funds on the blockchain are unaffected.",
    )
    Field("Type ERASE to confirm", name) { name = it }
    Button(
        onClick = {
            execute("Local portfolio data erased") {
                app.backup.wipe()
                applyAutomaticRefresh(app, host, false)
            }
            close()
        },
        enabled =
            name == "ERASE",
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
    ) { Text("Erase this device’s data") }
}
