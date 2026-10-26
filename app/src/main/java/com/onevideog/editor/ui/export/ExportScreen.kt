package com.onevideog.editor.ui.export

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.core.content.ContextCompat
import com.onevideog.editor.R
import com.onevideog.editor.data.ExportResolutionEnum
import com.onevideog.editor.data.SettingsRepository
import com.onevideog.editor.editor.ExportBus
import com.onevideog.editor.editor.ExportConfig
import com.onevideog.editor.editor.ExportManager
import com.onevideog.editor.editor.ExportService
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.ProjectMath
import com.onevideog.editor.ui.common.GraphiteButton
import com.onevideog.editor.ui.common.OvgFilterChip
import com.onevideog.editor.ui.common.OvgSectionCard
import com.onevideog.editor.ui.common.OvgTopBar
import com.onevideog.editor.ui.home.formatDuration
import com.onevideog.editor.ui.theme.GlazedOrangeButton

/** Localized, actionable failure descriptions for the export state machine. */
@Composable
internal fun exportFailureText(failure: ExportManager.Failure?): String = when (failure) {
    ExportManager.Failure.INSUFFICIENT_STORAGE -> stringResource(R.string.error_storage)
    ExportManager.Failure.IO_READ -> stringResource(R.string.error_read_source)
    ExportManager.Failure.IO_WRITE -> stringResource(R.string.error_write_output)
    ExportManager.Failure.ENCODING -> stringResource(R.string.error_encoding)
    ExportManager.Failure.DECODING -> stringResource(R.string.error_decoding)
    ExportManager.Failure.MUXING -> stringResource(R.string.error_muxing)
    ExportManager.Failure.EXPORT_ERROR -> stringResource(R.string.error_export_failed)
    ExportManager.Failure.COPY_ERROR -> stringResource(R.string.error_file_unreadable)
    ExportManager.Failure.VALIDATION_FAILED -> stringResource(R.string.error_no_video)
    null, ExportManager.Failure.GENERIC -> stringResource(R.string.error_generic)
}

/**
 * Export screen: resolution + quality presets, SAF destination picker, then
 * the work happens in [ExportService] (foreground) so long exports survive
 * screen-off/backgrounding. State arrives via [ExportBus]; progress,
 * finalization, cancellation and mapped failure reasons are all real.
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

    val bus by ExportBus.snapshot.collectAsState()
    val busState = if (bus.projectId == projectId) bus.state else ExportManager.State.Idle

    // Keep the screen on while the user watches the export screen (the
    // foreground service keeps the export alive regardless).
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { uri ->
        if (uri != null && uri != Uri.EMPTY) {
            val currentProject = viewModel.ui.value.project ?: return@rememberLauncherForActivityResult
            viewModel.saveNow {
                // Stage A evidence (user export request), before the service
                // starts. Destination is logged as provider fingerprint only.
                com.onevideog.editor.diag.DiagnosticsLogger.i(
                    "export", "REQUEST", null,
                    "user requested export",
                    meta = listOf(
                        "resolution" to resolution.name,
                        "quality" to quality.name,
                        "durationMs" to ProjectMath.projectDurationMs(currentProject).toString(),
                        "clips" to currentProject.clips.size.toString(),
                        "overlays" to currentProject.overlays.size.toString(),
                        "audio" to currentProject.audioClips.size.toString(),
                    ),
                )
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                ContextCompat.startForegroundService(
                    context,
                    ExportService.startIntent(
                        context,
                        currentProject.id,
                        resolution,
                        quality,
                        uri,
                    ),
                )
            }
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
                val durationMs = ProjectMath.projectDurationMs(project)
                Text(
                    stringResource(
                        R.string.clips_and_duration,
                        project.clips.size,
                        formatDuration(durationMs),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (project.audioClips.isNotEmpty()) {
                    Text(
                        stringResource(R.string.export_audio_tracks, project.audioClips.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OvgSectionCard {
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    Text(
                        stringResource(R.string.export_resolution),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExportResolutionEnum.entries.forEach { res ->
                            OvgFilterChip(
                                selected = resolution == res,
                                onClick = { resolution = res },
                                label = stringResource(res.labelRes),
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.fps_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            OvgSectionCard {
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    Text(
                        stringResource(R.string.export_quality),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            ExportConfig.Quality.LOW to R.string.quality_low,
                            ExportConfig.Quality.MEDIUM to R.string.quality_medium,
                            ExportConfig.Quality.HIGH to R.string.quality_high,
                        ).forEach { (q, label) ->
                            OvgFilterChip(
                                selected = quality == q,
                                onClick = { quality = q },
                                label = stringResource(label),
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text(stringResource(R.string.export_output_name)) },
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            val busy = busState is ExportManager.State.Queued ||
                busState is ExportManager.State.Running ||
                busState is ExportManager.State.Finalizing
            // v0.5.1: a project WITHOUT base video is exportable — the
            // composition renders a black background (initial 5 s, growing
            // with the clips). Only a zero-duration project blocks.
            val exportable = project != null &&
                com.onevideog.editor.editor.ProjectMath.projectDurationMs(project) > 0
            val canStart = exportable && !busy
            if (project != null && project.clips.isEmpty() && exportable) {
                Text(
                    stringResource(R.string.export_black_bg_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (project != null && !exportable) {
                Text(
                    stringResource(R.string.export_need_clips),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            when (val s = busState) {
                is ExportManager.State.Queued -> {
                    Text(
                        stringResource(R.string.export_queued),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    GraphiteButton(onClick = {
                        context.startService(ExportService.cancelIntent(context))
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                is ExportManager.State.Running -> {
                    Text(
                        stringResource(R.string.export_running, s.progressPercent),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    LinearProgressIndicator(
                        progress = { s.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.export_service_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GraphiteButton(onClick = {
                        context.startService(ExportService.cancelIntent(context))
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                is ExportManager.State.Finalizing -> {
                    Text(
                        stringResource(R.string.export_finalizing),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is ExportManager.State.Done -> {
                    Text(
                        stringResource(R.string.export_done_title),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.export_done_body),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    GraphiteButton(onClick = { ExportBus.reset() }) {
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
                        exportFailureText(s.failure),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.export_failed_diagnostics),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GraphiteButton(onClick = { ExportBus.reset() }) {
                        Text(stringResource(R.string.retry))
                    }
                }
                is ExportManager.State.Cancelled -> {
                    Text(
                        stringResource(R.string.export_canceled),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    GraphiteButton(onClick = { ExportBus.reset() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
                else -> {
                    GlazedOrangeButton(
                        onClick = {
                            val safe = ExportConfig.sanitizeFileName(fileName)
                            createDocument.launch("$safe.mp4")
                        },
                        enabled = canStart,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(R.string.export_start),
                            color = com.onevideog.editor.ui.theme.OnOrange,
                        )
                    }
                }
            }
        }
    }
}
