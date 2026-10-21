package com.onevideog.editor.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.ProjectRepository
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.ui.common.ConfirmDialog
import com.onevideog.editor.ui.common.OvgTopBar
import com.onevideog.editor.ui.common.TextInputDialog
import com.onevideog.editor.ui.editor.queryDisplayName
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Project home screen: list existing projects (thumbnail, name, metadata),
 * create, rename and delete. Fully offline.
 */
@Composable
fun ProjectsScreen(
    onOpenProject: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: EditorViewModel,
) {
    val context = LocalContext.current
    val repository = remember { ProjectRepository(context) }
    var projects by remember { mutableStateOf<List<OvgProject>>(emptyList()) }
    var showNewDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<OvgProject?>(null) }
    var deleteTarget by remember { mutableStateOf<OvgProject?>(null) }

    // v0.5.2 anti-duplication: set on the FIRST creation trigger (empty-project
    // tap or a picked video) and never reset while this screen stays composed —
    // a second tap cannot create a second project. Navigation replaces this
    // screen, so the guard resets naturally on return.
    var creating by remember { mutableStateOf(false) }

    suspend fun reload() {
        projects = withContext(Dispatchers.IO) { repository.list() }
    }

    LaunchedEffect(Unit) { reload() }

    val defaultName = stringResource(R.string.project_default_name, DateFormat.getDateInstance(DateFormat.SHORT).format(Date()))
    val defaultEmptyName = stringResource(R.string.empty_project_name)

    // v0.5.2 "Novo projeto" flow: name -> video picker -> create WITH the
    // video. The project is created only in THIS callback (never in a
    // LaunchedEffect, never on screen entry); a cancelled pick (null uri)
    // creates nothing and simply returns to the list.
    var newProjectName by remember { mutableStateOf<String?>(null) }
    val newProjectVideoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val name = newProjectName
        newProjectName = null
        if (uri != null && name != null && !creating) {
            creating = true
            val displayName = queryDisplayName(context, uri) ?: "video"
            viewModel.newProjectWithVideo(name, uri, displayName) { id ->
                onOpenProject(id)
            }
        }
    }

    Scaffold(
        topBar = {
            OvgTopBar(
                title = stringResource(R.string.home_title),
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.a11y_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                // v0.5.1: "Projeto vazio" — a video-less project (black
                // background timeline, 5 s starting duration) next to the
                // regular new-project action. v0.5.2: guarded — disabled on
                // the first touch so a double tap can never create twice.
                ExtendedFloatingActionButton(
                    onClick = {
                        if (!creating) {
                            creating = true
                            viewModel.newProject(defaultEmptyName) { id -> onOpenProject(id) }
                        }
                    },
                    icon = { Icon(Icons.Filled.VideocamOff, contentDescription = null) },
                    text = { Text(stringResource(R.string.empty_project)) },
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                ExtendedFloatingActionButton(
                    onClick = { if (!creating) showNewDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_project)) },
                )
            }
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(56.dp),
                )
                Text(
                    stringResource(R.string.projects_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    stringResource(R.string.projects_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        repository = repository,
                        onOpen = { onOpenProject(project.id) },
                        onRename = { renameTarget = project },
                        onDelete = { deleteTarget = project },
                    )
                }
            }
        }
    }

    if (showNewDialog) {
        TextInputDialog(
            title = stringResource(R.string.new_project),
            initial = defaultName,
            hint = stringResource(R.string.project_name_hint),
            confirmLabel = stringResource(R.string.new_project),
            // v0.5.2: the confirm does NOT create anything — it opens the
            // video picker. The project is created (once) only when a video
            // comes back; cancelling the picker returns to the list empty-handed.
            onConfirm = { name ->
                showNewDialog = false
                if (!creating) {
                    newProjectName = name
                    newProjectVideoLauncher.launch(arrayOf("video/*"))
                }
            },
            onDismiss = { showNewDialog = false },
        )
    }

    renameTarget?.let { target ->
        TextInputDialog(
            title = stringResource(R.string.rename_project),
            initial = target.name,
            hint = stringResource(R.string.project_name_hint),
            confirmLabel = stringResource(R.string.save),
            onConfirm = { name ->
                renameTarget = null
                kotlinx.coroutines.MainScope().launch {
                    withContext(Dispatchers.IO) { repository.rename(target.id, name) }
                    reload()
                }            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.delete_project_confirm_title),
            body = stringResource(R.string.delete_project_confirm_body, target.name),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                deleteTarget = null
                kotlinx.coroutines.MainScope().launch {
                    withContext(Dispatchers.IO) { repository.delete(target.id) }
                    reload()
                }            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ProjectCard(
    project: OvgProject,
    repository: ProjectRepository,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val thumbFile = remember(project.id, project.updatedAtEpochMs) {
        repository.homeThumbnailFile(project.id)
    }
    val dateText = remember(project.updatedAtEpochMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(project.updatedAtEpochMs))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbFile != null && thumbFile.exists()) {
                    var imageBitmap by remember(thumbFile.absolutePath) {
                        mutableStateOf<ImageBitmap?>(null)
                    }
                    LaunchedEffect(thumbFile.absolutePath) {
                        imageBitmap = withContext(Dispatchers.IO) {
                            android.graphics.BitmapFactory.decodeFile(thumbFile.absolutePath)
                                ?.asImageBitmap()
                        }
                    }
                    imageBitmap?.let { bmp ->
                        androidx.compose.foundation.Image(
                            bitmap = bmp,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                Text(
                    stringResource(
                        R.string.clips_and_duration,
                        project.clips.size,
                        formatDuration(com.onevideog.editor.editor.ProjectMath.projectDurationMs(project)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    dateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename_project)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
