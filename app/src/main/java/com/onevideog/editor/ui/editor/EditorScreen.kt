package com.onevideog.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Videocam
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.onevideog.editor.R
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.ui.home.formatDuration

/**
 * Editing screen: composition preview ([PreviewStage]), multi-track timeline
 * ([TimelineStrip]) and contextual tool sheets. Designed for one-handed
 * portrait use on phones with 4 GB RAM.
 *
 * v0.5.1 (empty project): the preview is ALWAYS composed — a project
 * without base video renders the black stage with its text/image overlays
 * and plays its audio tracks. Only a completely empty project shows the
 * hint, drawn OVER the black stage (never replacing it), and every clip
 * type is immediately addable (the duration floor is 5 s).
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
    // v0.6.0 (H5): the transition sheet is opened from the timeline badge
    // between two adjacent video clips (pair of clip ids).
    var transitionPair by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(projectId) { viewModel.open(projectId) }

    val project = state.project

    // v0.5.2: leaving the editor — through the top-bar back arrow OR the
    // system back gesture — flushes the pending save and auto-deletes the
    // project when it is completely empty (0 clips, 0 overlays, 0 audios).
    // Navigating to the EXPORT screen is not an exit: that project is kept.
    BackHandler(enabled = project != null) {
        viewModel.exitAndCleanup(onBack)
    }

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
                com.onevideog.editor.editor.MediaImporter.ImportException.Kind.IMAGE_UNREADABLE ->
                    context.getString(R.string.error_image_unreadable)
                else -> context.getString(R.string.error_file_unreadable)
            }
            viewModel.consumeImportError()
        }
    }
    LaunchedEffect(state.loadError) {
        if (state.loadError) errorText = context.getString(R.string.error_project_load)
    }
    val audioPreviewError by viewModel.audioPreviewError.collectAsState()
    LaunchedEffect(audioPreviewError) {
        if (audioPreviewError != null) {
            errorText = context.getString(R.string.error_audio_preview)
            viewModel.consumeAudioPreviewError()
        }
    }
    val playbackError by viewModel.playbackError.collectAsState()
    LaunchedEffect(playbackError) {
        if (playbackError != null) {
            errorText = context.getString(R.string.error_playback)
            viewModel.consumePlaybackError()
        }
    }
    val editError by viewModel.editError.collectAsState()
    LaunchedEffect(editError) {
        editError?.let {
            errorText = when (it) {
                EditorViewModel.EditError.NO_CLIP -> context.getString(R.string.split_no_clip)
                EditorViewModel.EditError.SPLIT_TOO_SHORT ->
                    context.getString(R.string.split_too_short)
            }
            viewModel.consumeEditError()
        }
    }

    // v0.5.4 (docs/AUDIT_v0.5.3_problems.md §1): the fixed chrome below the
    // stage (top bar + transform panel + transport + 220 dp timeline + tool
    // row ≈ 474 dp collapsed) can exceed the window height — landscape, and
    // portrait with the transform panel expanded — starving the weight(1f)
    // stage down to a 53×30 px sliver (the reported surface change) and
    // corrupting overlay gestures. Two guards:
    //  - compact windows (< 660 dp tall, e.g. landscape) render the editor
    //    in a vertically scrollable column with a fixed 200 dp stage — every
    //    control stays reachable and the preview keeps a usable size;
    //  - normal portrait keeps the exact v0.5.3 flex layout with a 140 dp
    //    hard floor so the preview can never collapse again.
    // v0.5.5: the second guard's floor was a no-op (weight measured the stage
    // with min=max=leftover, so heightIn could not lift it — the 53×30 sliver
    // recurred at 16:00:17 on v0.5.4). The flex branch now caps the chrome
    // below the stage (see the Ponto 2 comment at the cap); the floor holds
    // by construction. The v0.5.4 gesture hard-stop stays as defence-in-depth.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactWindow = maxHeight < 660.dp
        // Captured for the flex-branch chrome cap: BoxWithConstraintsScope
        // members are not readable through the outer Column's implicit
        // receiver (compiler: "cannot be called in this context").
        val windowMaxHeight = maxHeight

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
                // v0.5.2: save + auto-delete-if-empty, then navigate.
                viewModel.exitAndCleanup(onBack)
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

        // The stage + everything below it. ONE definition of each child so
        // the compact and flex branches can never drift.
        val stageContent: @Composable () -> Unit = {
            // v0.5.1: the preview stage (black background + overlay layer +
            // audio preview) exists for EVERY project — empty ones included.
            PreviewStage(viewModel = viewModel)
            if (project.clips.isEmpty() && project.overlays.isEmpty() && project.audioClips.isEmpty()) {
                // Hint only — drawn over the black stage, not replacing it.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.editor_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.editor_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        val chromeBelow: @Composable () -> Unit = {
            // v0.4.0 (Etapa E1/E4): contextual transform panel for the selected
            // text/image layer. Inline (not modal) so the preview element stays
            // visible and touchable; collapsed to one row until expanded; its
            // commits go through the same updateOverlay path as the gizmos (one
            // undo step per control) and never rebuild the player or effects.
            TransformQuickPanel(
                viewModel = viewModel,
                onOpenEditor = { activeTool = it },
            )

            // Transport bar: play/pause + seek + project time, BELOW the canvas.
            // The preview area stays free for selecting/manipulating overlays.
            TransportBar(viewModel = viewModel)

            TimelineStrip(
                viewModel = viewModel,
                onAddAudio = { audioLauncher.launch(arrayOf("audio/*")) },
                onOpenTool = { activeTool = it },
                onOpenTransition = { pair -> transitionPair = pair },
            )

            ToolBarRow(
                onImport = {
                    importLauncher.launch(arrayOf("video/*"))
                },
                onTool = { activeTool = it },
                hasContent = com.onevideog.editor.editor.ProjectMath.projectDurationMs(project) > 0,
            )
        }

        if (compactWindow) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    stageContent()
                }
                chromeBelow()
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 140.dp),
                contentAlignment = Alignment.Center,
            ) {
                stageContent()
            }
            // v0.5.5 (docs/AUDIT_v0.5.4_report_followup.md §2): the v0.5.4
            // `heightIn(min = 140.dp)` above is a NO-OP exactly when it
            // matters — `weight(1f)` measures this Box with min = max = the
            // leftover space, so the heightIn min is coerced INTO
            // [leftover, leftover] and can never lift it (device evidence
            // 2026-10-21 16:00:17: containerH=30 px with the floor in the
            // build). Instead of fighting the weight, the chrome below is
            // CAPPED so the weighted stage always receives at least the
            // floor: non-weighted children are measured first, so the stage
            // gets `maxHeight - topBar - cap >= 140 dp` by construction. When
            // the chrome content (expanded quick panel + timeline + tool row)
            // exceeds the cap it scrolls internally — the same pattern the
            // compact branch already ships. Normal portrait (chrome ~ 420 dp
            // < cap ~ 590 dp) is layout-identical to v0.5.4.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = windowMaxHeight - 212.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                chromeBelow()
            }
        }
    }
    }

    activeTool?.let { tool ->
        ToolSheet(
            tool = tool,
            viewModel = viewModel,
            onDismiss = { activeTool = null },
            onOpenTool = { activeTool = it },
        )
    }

    // v0.6.0 (H5): the transition sheet for the tapped adjacent pair.
    transitionPair?.let { pair ->
        TransitionSheet(
            viewModel = viewModel,
            clipIdA = pair.first,
            clipIdB = pair.second,
            onDismiss = { transitionPair = null },
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

/**
 * Compact transport row placed directly below the preview canvas: play/pause,
 * ±10 s seek and the authoritative project time. Buttons keep ≥ 48 dp touch
 * targets for small phones.
 */
@Composable
private fun TransportBar(viewModel: EditorViewModel) {
    val state by viewModel.ui.collectAsState()
    val durationMs = state.project?.let { com.onevideog.editor.editor.ProjectMath.projectDurationMs(it) } ?: 0L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(onClick = { viewModel.seekTo(state.playheadMs - 10_000) }, modifier = Modifier.size(46.dp)) {
            Icon(
                Icons.Filled.Replay10,
                contentDescription = stringResource(R.string.a11y_back_10s),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        IconButton(
            onClick = { viewModel.setPlaying(!state.isPlaying) },
            modifier = Modifier.size(46.dp),
        ) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (state.isPlaying) R.string.a11y_pause else R.string.a11y_play,
                ),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
        }
        IconButton(onClick = { viewModel.seekTo(state.playheadMs + 10_000) }, modifier = Modifier.size(46.dp)) {
            Icon(
                Icons.Filled.Forward10,
                contentDescription = stringResource(R.string.a11y_forward_10s),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            formatDuration(state.playheadMs) + " / " + formatDuration(durationMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (state.isScrubbing) {
            Text(
                stringResource(R.string.scrubbing_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * v0.8.1 (BUG B fix — device report: the FORMAT/aspect tool was unreachable):
 * the ONE toolbar list — tool order and icon, rendered by [ToolBarRow].
 *
 * Root cause of the device report: the tool set grew across releases
 * (v0.7.0 added CURVES + SPEED_RAMP) while the Row stayed non-scrollable —
 * 13 tools + 2 import buttons at 44 dp = 660 dp of content on a ~360-393 dp
 * screen, so every button past the edge was silently clipped. The ASPECT
 * tool sat exactly at slot 9 — the first one off-screen. The row is now
 * horizontally scrollable and the list is TOP-LEVEL so the coverage test
 * (ToolBarCoverageTest) can prove every Tool enum entry has an icon slot —
 * the toolbar can never silently drop a tool again. (Sheet coverage is
 * compile-enforced: ToolSheet's `when` has no else branch, so a new Tool
 * without a panel fails the build.)
 */
internal val TOOLBAR_TOOLS: List<Pair<Tool, androidx.compose.ui.graphics.vector.ImageVector>> = listOf(
    Tool.LAYERS to Icons.Filled.Layers,
    Tool.TRIM to Icons.Filled.ContentCut,
    Tool.SPLIT to Icons.Filled.Add,
    Tool.VOLUME to Icons.Filled.VolumeUp,
    Tool.SPEED to Icons.Filled.Speed,
    Tool.SPEED_RAMP to Icons.Filled.Timeline,
    Tool.ASPECT to Icons.Filled.AspectRatio,
    Tool.COLOR to Icons.Filled.Palette,
    Tool.CURVES to Icons.Filled.Tune,
    Tool.EFFECTS to Icons.Filled.BlurOn,
    // v0.9.0 (Fase L): depth/camera (3D box) + style (sparkles) — distinct
    // icons, ToolBarCoverageTest-enforced.
    Tool.DEPTH to Icons.Filled.ViewInAr,
    Tool.STYLE to Icons.Filled.AutoAwesome,
    Tool.TEXT to Icons.Filled.TextFields,
    Tool.IMAGE to Icons.Filled.Photo,
    Tool.AUDIO to Icons.Filled.AudioFile,
)

@Composable
private fun ToolBarRow(
    onImport: () -> Unit,
    onTool: (Tool) -> Unit,
    hasContent: Boolean,
) {
    val tools = TOOLBAR_TOOLS
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // v0.8.1 (BUG B): the row overflows on every phone width (15
            // x 44 dp buttons) — scroll it so NO tool is ever unreachable
            // (the device report's missing FORMAT tool was clipped, not
            // removed).
            .horizontalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(onClick = onImport, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.a11y_import_video),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        // v0.5.2: the camera button IS "Adicionar vídeo" — always active,
        // opens the video selector on the CURRENT project. The imported video
        // becomes the base of a video-less project (duration = video length,
        // existing overlays kept) through the one shared [importVideo] path.
        // The v0.5.1 behaviour (create a NEW empty project from inside the
        // editor) is intentionally gone — projects are only created on the
        // home screen ("Novo projeto" / "Projeto vazio").
        IconButton(onClick = onImport, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Filled.Videocam,
                contentDescription = stringResource(R.string.a11y_add_video),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        tools.forEach { (tool, icon) ->
            IconButton(
                onClick = { onTool(tool) },
                // Layers/aspect are always available; editing tools need a
                // non-empty timeline (overlays/audio can exist without clips).
                enabled = hasContent || tool == Tool.AUDIO || tool == Tool.ASPECT ||
                    tool == Tool.LAYERS,
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
