package com.onevideog.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.IosShare
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.onevideog.editor.R
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.TimelineLogic
import com.onevideog.editor.ui.common.EmptyState
import com.onevideog.editor.ui.home.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Editing screen: video preview (ExoPlayer), timeline strip, contextual
 * bottom tool bar and tool sheets. Designed for one-handed portrait use.
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
            if (project.clips.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.editor_empty_title),
                    body = stringResource(R.string.editor_empty_body),
                )
            } else {
                PreviewArea(viewModel = viewModel)
            }
        }

        TimelineStrip(viewModel = viewModel)

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
        androidx.compose.material3.FilledTonalButton(onClick = onExport) {
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
private fun PreviewArea(viewModel: EditorViewModel) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    // Which timeline clip is under the playhead.
    val clipIndex = TimelineLogic.clipIndexAt(project.clips, state.playheadMs)
    val currentClip = project.clips.getOrNull(clipIndex)
    val clipStart = TimelineLogic.clipStartMs(project.clips, clipIndex)

    // (Re)load the media item whenever the active clip changes.
    var loadedClipId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(currentClip?.id, viewModel.seekToken.collectAsState().value) {
        if (currentClip == null) {
            player.stop()
            loadedClipId = null
            return@LaunchedEffect
        }
        val localMs = currentClip.trimStartMs +
            Math.round((state.playheadMs - clipStart).coerceAtLeast(0) * currentClip.speed)
        val item = MediaItem.Builder()
            .setUri(android.net.Uri.parse(currentClip.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(currentClip.trimStartMs)
                    .setEndPositionMs(currentClip.trimEndMs)
                    .build(),
            )
            .build()
        player.setMediaItem(item, localMs.coerceAtMost(currentClip.trimEndMs))
        player.setPlaybackSpeed(currentClip.speed)
        player.prepare()
        player.playWhenReady = state.isPlaying
        loadedClipId = currentClip.id
    }

    // Poll player position back into the view model while playing.
    LaunchedEffect(state.isPlaying, loadedClipId) {
        while (isActive && state.isPlaying && currentClip != null) {
            val localMedia = player.currentPosition
            val timelineMs = clipStart +
                Math.round((localMedia - currentClip.trimStartMs).coerceAtLeast(0) / currentClip.speed)
            val clipEnd = clipStart + currentClip.effectiveDurationMs
            if (localMedia >= currentClip.trimEndMs - 30) {
                if (clipIndex < project.clips.lastIndex) {
                    viewModel.seekTo(clipEnd)
                } else {
                    viewModel.setPlaying(false)
                    viewModel.seekTo(clipEnd)
                }
            } else {
                viewModel.setPlayhead(timelineMs)
            }
            delay(80)
        }
    }

    Column {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction = 0.82f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                viewModel.setPlaying(!state.isPlaying)
                player.playWhenReady = !state.isPlaying
            }) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(
                        if (state.isPlaying) R.string.a11y_pause else R.string.a11y_play,
                    ),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )
            }
            Text(
                formatDuration(state.playheadMs) + " / " + formatDuration(project.totalDurationMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatDuration(project.totalDurationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Tool.MUSIC to Icons.Filled.MusicNote,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(onClick = onImport, modifier = Modifier.size(44.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.a11y_import_video),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        tools.forEach { (tool, icon) ->
            IconButton(
                onClick = { onTool(tool) },
                enabled = hasClips || tool == Tool.MUSIC,
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
