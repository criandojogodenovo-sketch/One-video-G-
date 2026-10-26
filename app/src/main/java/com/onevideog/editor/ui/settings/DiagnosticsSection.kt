package com.onevideog.editor.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.SettingsRepository
import com.onevideog.editor.diag.DiagnosticsLogger
import com.onevideog.editor.ui.common.OvgAlertDialog
import com.onevideog.editor.ui.common.OvgSectionCard
import com.onevideog.editor.ui.common.OvgSwitchRow
import com.onevideog.editor.ui.theme.GraphiteButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Temporary diagnostics section (v0.2.3 — evidence-collection build).
 *
 * Lives in Settings. Everything here is explicit and on-device:
 *  - opt-in toggle (OFF by default, persisted);
 *  - live status + event count + approximate size;
 *  - Copy Diagnostic Report (clipboard, sanitized);
 *  - Clear Logs (with confirmation);
 *  - Export Diagnostic Report (Storage Access Framework, user-chosen file);
 *  - a visible privacy notice. No telemetry, no automatic upload.
 *
 * v0.9.3: restyled with the shared kit — graphite section card, orange
 * switch row, graphite buttons and the one themed alert dialog (no generic
 * Material 3 default look).
 */
@Composable
fun DiagnosticsSection(settings: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by settings.diagnosticsEnabled.collectAsState()
    val stats by DiagnosticsLogger.stats.collectAsState()

    var showClearConfirm by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val report = DiagnosticsLogger.buildReport()
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(report.toByteArray(Charsets.UTF_8))
                    out.flush()
                    true
                } ?: false
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (ok) R.string.diag_export_saved else R.string.diag_export_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.diag_section_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        OvgSectionCard {
            OvgSwitchRow(
                label = stringResource(R.string.diag_toggle_label),
                checked = enabled,
                onCheckedChange = { settings.setDiagnosticsEnabled(it) },
            )
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                Text(
                    stringResource(
                        if (enabled) R.string.diag_status_on else R.string.diag_status_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.diag_stats_line, stats.events, stats.approxKb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        GraphiteButton(onClick = {
            val ok = runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(
                    ClipData.newPlainText("One Video G diagnostics", DiagnosticsLogger.buildReport()),
                )
            }.isSuccess
            Toast.makeText(
                context,
                if (ok) R.string.diag_copied else R.string.diag_copy_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }) {
            Text(stringResource(R.string.diag_copy_report))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GraphiteButton(onClick = { showClearConfirm = true }) {
                Text(stringResource(R.string.diag_clear_logs))
            }
            GraphiteButton(onClick = {
                exportLauncher.launch("one-video-g-diagnostic-report.txt")
            }) {
                Text(stringResource(R.string.diag_export_report))
            }
        }

        Text(
            stringResource(R.string.diag_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showClearConfirm) {
        OvgAlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = stringResource(R.string.diag_clear_confirm_title),
            body = stringResource(R.string.diag_clear_confirm_body),
            confirmLabel = stringResource(R.string.diag_clear_confirm_yes),
            onConfirm = {
                DiagnosticsLogger.clearAll()
                showClearConfirm = false
            },
            dismissLabel = stringResource(R.string.cancel),
            onDismiss = { showClearConfirm = false },
            destructiveConfirm = true,
        )
    }
}
