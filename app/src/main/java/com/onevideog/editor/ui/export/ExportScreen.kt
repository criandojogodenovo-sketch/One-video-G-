package com.onevideog.editor.ui.export

import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.ExportResolutionEnum
import com.onevideog.editor.data.SettingsRepository
import com.onevideog.editor.editor.ExportConfig
import com.onevideog.editor.editor.ExportManager
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.ui.common.OvgTopBar
import com.onevideog.editor.ui.home.formatDuration
import kotlinx.coroutines.launch

/**
 * Export screen: resolution + quality presets, SAF destination picker, real
 * progress, cancellation and validation. Builds the release-quality MP4 with
 * Media3 Transformer entirely offline.
 */
@Composable
fun ExportScreen(
    projectId: String,
    viewModel: EditorViewModel,
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.ui.collectAsState()
    val project = state.project
    val defaultRes by settings.defaultResolution.collectAsState()

    var resolution by remember { mutableStateOf(defaultRes) }
    var quality by remember { mutableStateOf(ExportConfig.Quality.MEDIUM) }
    var fileName by remember { mutableStateOf("") }

    LaunchedEffect(projectId) {
        if (viewModel.ui.value.project?.id != projectId) viewModel.open(projectId)
        fileName = ExportConfig.sanitizeFileName(viewModel.ui.value.project?.name ?: "one-video-g")
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val exportState = remember { mutableStateOf<ExportManager.State>(ExportManager.State.Idle) }
    val exportManager = remember {
        ExportManager(context.applicationContext, scope).apply {
            onState = { exportState.value = it }
        }
    }

    // Keep the screen on while exporting.
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            exportManager.cancel()
        }
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { uri ->
        if (uri != null) {
            exportManager.start(
                project = viewModel.ui.value.project ?: return@rememberLauncherForActivityResult,
                resolution = resolution,
                quality = quality,
                destinationUri = uri,
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OvgTopBar(title = stringResource(R.string.export_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (project != null) {
                Text(
                    stringResource(
                        R.string.clips_and_duration,
                        project.clips.size,
                        formatDuration(project.totalDurationMs),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(stringResource(R.string.export_resolution), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportResolutionEnum.entries.forEach { res ->
                    FilterChip(
                        selected = resolution == res,
                        onClick = { resolution = res },
                        label = { Text(stringResource(res.labelRes)) },
                    )
                }
            }
            Text(
                stringResource(R.string.fps_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(stringResource(R.string.export_quality), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ExportConfig.Quality.LOW to R.string.quality_low,
                    ExportConfig.Quality.MEDIUM to R.string.quality_medium,
                    ExportConfig.Quality.HIGH to R.string.quality_high,
                ).forEach { (q, label) ->
                    FilterChip(
                        selected = quality == q,
                        onClick = { quality = q },
                        label = { Text(stringResource(label)) },
                    )
                }
            }

            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text(stringResource(R.string.export_output_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val canStart = project != null && project.clips.isNotEmpty() && !exportManager.isRunning &&
                exportState.value !is ExportManager.State.Running &&
                exportState.value !is ExportManager.State.Verifying
            if (project != null && project.clips.isEmpty()) {
                Text(
                    stringResource(R.string.export_need_clips),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            when (val s = exportState.value) {
                is ExportManager.State.Running -> {
                    Text(stringResource(R.string.export_running, s.progressPercent))
                    LinearProgressIndicator(
                        progress = { s.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = { exportManager.cancel() }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                is ExportManager.State.Verifying -> {
                    Text(stringResource(R.string.export_verifying))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is ExportManager.State.Done -> {
                    Text(
                        stringResource(R.string.export_done_title),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.export_done_body))
                    Button(onClick = { exportState.value = ExportManager.State.Idle }) {
                        Text(stringResource(R.string.close))
                    }
                }
                is ExportManager.State.Failed -> {
                    Text(
                        stringResource(R.string.export_failed_title),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            when (s.message) {
                                "error_storage" -> R.string.error_storage
                                "error_no_video" -> R.string.error_no_video
                                "error_file_unreadable" -> R.string.error_file_unreadable
                                else -> R.string.error_export_failed
                            },
                        ),
                    )
                    Button(onClick = { exportState.value = ExportManager.State.Idle }) {
                        Text(stringResource(R.string.retry))
                    }
                }
                is ExportManager.State.Cancelled -> {
                    Text(stringResource(R.string.export_canceled))
                    Button(onClick = { exportState.value = ExportManager.State.Idle }) {
                        Text(stringResource(R.string.ok))
                    }
                }
                else -> {
                    Button(
                        onClick = {
                            val safe = ExportConfig.sanitizeFileName(fileName)
                            createDocument.launch("$safe.mp4")
                        },
                        enabled = canStart,
                    ) {
                        Text(stringResource(R.string.export_start))
                    }
                }
            }
        }
    }
}
