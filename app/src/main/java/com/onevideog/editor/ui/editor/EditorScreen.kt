package com.onevideog.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.onevideog.editor.R
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.TimelineLogic
import com.onevideog.editor.ui.common.EmptyState

/**
 * Editing screen: composition preview ([PreviewStage]), multi-track timeline
 * ([TimelineStrip]) and contextual tool sheets. Designed for one-handed
 * portrait use on phones with 4 GB RAM.
 */
@Composable
fun EditorScreen(
    projectId: String,
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onExport: (String) -> Unit,
) {
    val state by viewModel.ui.collectAsState()
    val context = LocalContext.current
    var activeTool by remember { mutableStateOf<Tool?>(null) }

    LaunchedEffect(projectId) { viewModel.open(projectId) }

    val project = state.project

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "video"
            viewModel.importVideo(uri, name)
        }
    }
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "audio"
            viewModel.addAudioClip(uri, name)
        }
    }

    // Import error snackbar mapping
    val importError by viewModel.importError.collectAsState()
    var errorText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(importError) {
        importError?.let {
            errorText = when (it) {
                com.onevideog.editor.editor.MediaImporter.ImportException.Kind.UNSUPPORTED ->
                    context.getString(R.string.error_unsupported_file)
                com.onevideog.editor.editor.MediaImporter.ImportException.Kind.NO_VIDEO ->
                    context.getString(R.string.error_no_video)
                else -> context.getString(R.string.error_file_unreadable)
            }
            viewModel.consumeImportError()
        }
    }
    LaunchedEffect(state.loadError) {
        if (state.loadError) errorText = context.getString(R.string.error_project_load)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        EditorTopBar(
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            onUndo = { viewModel.undo() },
            onRedo = { viewModel.redo() },
            onBack = {
                viewModel.saveNow()
                onBack()
            },
            onExport = { viewModel.saveNow { onExport(projectId) } },
        )

        if (project == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (!state.loadError) {
                    Text(stringResource(R.string.export_preparing))
                } else {
                    Text(stringResource(R.string.error_project_load))
                }
            }
            return@Column
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (project.clips.isEmpty() && project.overlays.isEmpty() && project.audioClips.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.editor_empty_title),
                    body = stringResource(R.string.editor_empty_body),
                )
            } else {
                PreviewStage(viewModel = viewModel)
            }
        }

        TimelineStrip(
            viewModel = viewModel,
            onAddAudio = { audioLauncher.launch(arrayOf("audio/*")) },
            onOpenTool = { activeTool = it },
        )

        ToolBarRow(
            onImport = {
                importLauncher.launch(arrayOf("video/*"))
            },
            onTool = { activeTool = it },
            hasClips = project.clips.isNotEmpty(),
        )
    }

    activeTool?.let { tool ->
        ToolSheet(
            tool = tool,
            viewModel = viewModel,
            onDismiss = { activeTool = null },
        )
    }

    errorText?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text(stringResource(R.string.error_generic)) },
            text = { Text(message) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { errorText = null }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.a11y_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = stringResource(R.string.a11y_undo),
                tint = if (canUndo) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.outline,
            )
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(
                Icons.AutoMirrored.Filled.Redo,
                contentDescription = stringResource(R.string.a11y_redo),
                tint = if (canRedo) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.outline,
            )
        }
        FilledTonalButton(onClick = onExport) {
            Icon(
                Icons.Filled.IosShare,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.export_title))
        }
    }
}

@Composable
private fun ToolBarRow(
    onImport: () -> Unit,
    onTool: (Tool) -> Unit,
    hasClips: Boolean,
) {
    val tools = remember {
        listOf(
            Tool.TRIM to Icons.Filled.ContentCut,
            Tool.SPLIT to Icons.Filled.Add,
            Tool.VOLUME to Icons.Filled.VolumeUp,
            Tool.SPEED to Icons.Filled.Speed,
            Tool.ASPECT to Icons.Filled.AspectRatio,
            Tool.COLOR to Icons.Filled.Palette,
            Tool.TEXT to Icons.Filled.TextFields,
            Tool.IMAGE to Icons.Filled.Photo,
            Tool.AUDIO to Icons.Filled.AudioFile,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(onClick = onImport, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.a11y_import_video),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        tools.forEach { (tool, icon) ->
            IconButton(
                onClick = { onTool(tool) },
                enabled = hasClips || tool == Tool.AUDIO || tool == Tool.ASPECT,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = stringResource(tool.labelRes),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

/** Resolves a display name for a SAF document (best effort). */
internal fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? {
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}
