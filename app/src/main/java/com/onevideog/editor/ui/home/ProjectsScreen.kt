package com.onevideog.editor.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onevideog.editor.R
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.ProjectRepository
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.ProjectMath
import com.onevideog.editor.ui.common.ConfirmDialog
import com.onevideog.editor.ui.common.TextInputDialog
import com.onevideog.editor.ui.theme.BgDeep
import com.onevideog.editor.ui.theme.BgElevated
import com.onevideog.editor.ui.theme.EdgeLight
import com.onevideog.editor.ui.theme.GlazedOrangeButton
import com.onevideog.editor.ui.theme.GraphiteButton
import com.onevideog.editor.ui.theme.OgWordmarkAnimation
import com.onevideog.editor.ui.theme.OnOrange
import com.onevideog.editor.ui.theme.OrangeMid
import com.onevideog.editor.ui.theme.TextFaint
import com.onevideog.editor.ui.theme.clickableNoRipple
import com.onevideog.editor.ui.theme.litEdge
import com.onevideog.editor.ui.theme.pressScale
import com.onevideog.editor.ui.theme.softGlow
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home shell — v0.9.2 redesign (Nova Editor inspired).
 *
 * Bottom bar: Início | Projetos | (+) | Presets | Perfil.
 * All project flows (create / rename / delete / open) keep the exact same
 * code paths and guards as the previous list screen; only the interface
 * changed:
 *  - v0.5.2 anti-duplication: `creating` set on the FIRST creation trigger
 *    and never reset while this screen stays composed.
 *  - v0.5.6: confirming the new-project dialog creates the internal project
 *    directly (viewModel.newProject -> onOpenProject -> editor/$id).
 *  - "Projeto vazio" kept as a first-class action on the Início tab.
 */
enum class HomeTab { HOME, PROJECTS, PRESETS, PROFILE }

@Composable
fun ProjectsScreen(
    onOpenProject: (String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: EditorViewModel,
) {
    val context = LocalContext.current
    val repository = remember { ProjectRepository(context) }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<OvgProject>>(emptyList()) }
    var selectedTab by remember { mutableStateOf(HomeTab.HOME) }
    var showNewDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<OvgProject?>(null) }
    var deleteTarget by remember { mutableStateOf<OvgProject?>(null) }

    // v0.5.2 anti-duplication guard — see class docs.
    var creating by remember { mutableStateOf(false) }

    suspend fun reload() {
        projects = withContext(Dispatchers.IO) { repository.list() }
    }

    LaunchedEffect(Unit) { reload() }

    val defaultName = stringResource(
        R.string.project_default_name,
        DateFormat.getDateInstance(DateFormat.SHORT).format(Date()),
    )
    val defaultEmptyName = stringResource(R.string.empty_project_name)

    fun createNamed(name: String) {
        if (!creating) {
            creating = true
            viewModel.newProject(name) { id -> onOpenProject(id) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0.0f to BgElevated,
                    0.30f to BgDeep,
                    1.0f to BgDeep,
                ),
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    // Fade + short slide, 240 ms in / 160 ms out (spec: 200-350 ms).
                    (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 20 })
                        .togetherWith(fadeOut(tween(160)))
                },
                label = "homeTabs",
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) { tab ->
                when (tab) {
                    HomeTab.HOME -> HomeTabContent(
                        projects = projects,
                        repository = repository,
                        onNewComposition = { if (!creating) showNewDialog = true },
                        onEmptyProject = {
                            if (!creating) {
                                creating = true
                                viewModel.newProject(defaultEmptyName) { id -> onOpenProject(id) }
                            }
                        },
                        onOpenSettings = onOpenSettings,
                        onOpenProfile = { selectedTab = HomeTab.PROFILE },
                        onOpenProjectsTab = { selectedTab = HomeTab.PROJECTS },
                        onOpenProject = onOpenProject,
                    )
                    HomeTab.PROJECTS -> ProjectsTabContent(
                        projects = projects,
                        repository = repository,
                        onOpenProject = onOpenProject,
                        onRename = { renameTarget = it },
                        onDelete = { deleteTarget = it },
                    )
                    HomeTab.PRESETS -> InDevelopmentTabContent(icon = { Icon(Icons.Filled.AutoAwesome, null) })
                    HomeTab.PROFILE -> InDevelopmentTabContent(profile = true)
                }
            }

            OvgBottomBar(
                selected = selectedTab,
                onSelect = { selectedTab = it },
                onNewComposition = { if (!creating) showNewDialog = true },
            )
        }
    }

    if (showNewDialog) {
        TextInputDialog(
            title = stringResource(R.string.new_project),
            initial = defaultName,
            hint = stringResource(R.string.project_name_hint),
            confirmLabel = stringResource(R.string.new_project),
            onConfirm = { name ->
                showNewDialog = false
                createNamed(name)
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
                scope.launch {
                    withContext(Dispatchers.IO) { repository.rename(target.id, name) }
                    reload()
                }
            },
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
                scope.launch {
                    withContext(Dispatchers.IO) { repository.delete(target.id) }
                    reload()
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

// ------------------------------------------------------------------ Início

@Composable
private fun HomeTabContent(
    projects: List<OvgProject>,
    repository: ProjectRepository,
    onNewComposition: () -> Unit,
    onEmptyProject: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenProjectsTab: () -> Unit,
    onOpenProject: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 10.dp, bottom = 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            HomeHeader(onOpenSettings = onOpenSettings, onOpenProfile = onOpenProfile)
        }
        item(key = "banner") { HeroBanner() }
        item(key = "wordmark") {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                OgWordmarkAnimation(fontSize = 64.sp)
            }
        }
        item(key = "newComposition") {
            GlazedOrangeButton(
                onClick = onNewComposition,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = OnOrange)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.new_composition),
                    style = MaterialTheme.typography.labelLarge,
                    color = OnOrange,
                    fontSize = 16.sp,
                )
            }
        }
        item(key = "emptyProject") {
            GraphiteButton(
                onClick = onEmptyProject,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Icon(
                    Icons.Filled.VideocamOff,
                    contentDescription = stringResource(R.string.a11y_empty_project),
                    tint = OrangeMid,
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.empty_project), style = MaterialTheme.typography.labelLarge)
            }
        }
        item(key = "projectsCard") {
            ProjectsSummaryCard(
                count = projects.size,
                onOpen = onOpenProjectsTab,
            )
        }
        item(key = "yourProjectsHeader") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.your_projects),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.see_all_projects),
                    style = MaterialTheme.typography.labelMedium,
                    color = OrangeMid,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onOpenProjectsTab)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        recentProjectItems(
            projects = projects.take(4),
            repository = repository,
            onOpenProject = onOpenProject,
            onRename = null,
            onDelete = null,
        )
    }
}

/** Top of the Início tab: logo + name on the left, avatar + settings right. */
@Composable
private fun HomeHeader(
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ovg_logo),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(38.dp)
                .softGlow(6.dp, CircleShape, OrangeMid)
                .clip(CircleShape)
                .litEdge(CircleShape, topAlpha = 0.35f, bottomAlpha = 0.10f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.home_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.weight(1f))
        // Avatar → Perfil tab.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .litEdge(CircleShape, topAlpha = 0.25f, bottomAlpha = 0.08f)
                .clickableNoRipple(remember { MutableInteractionSource() }, onOpenProfile),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Person,
                contentDescription = stringResource(R.string.a11y_avatar),
                tint = OrangeMid,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = stringResource(R.string.a11y_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Feature banner: blurred video + anime scissors, impact phrase on top. */
@Composable
private fun HeroBanner() {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp)
            .softGlow(14.dp, shape, OrangeMid)
            .clip(shape)
            .litEdge(shape, topAlpha = 0.30f, bottomAlpha = 0.05f),
    ) {
        Image(
            painter = painterResource(R.drawable.ovg_home_banner),
            contentDescription = stringResource(R.string.a11y_banner),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.30f),
                        0.42f to Color.Black.copy(alpha = 0.05f),
                        1.00f to Color.Black.copy(alpha = 0.86f),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(18.dp),
        ) {
            Text(
                stringResource(R.string.banner_phrase),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Black,
                    fontSize = 30.sp,
                ),
                color = Color.White,
            )
            Text(
                stringResource(R.string.banner_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.82f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Card that routes to the Projetos tab. */
@Composable
private fun ProjectsSummaryCard(
    count: Int,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0.0f to MaterialTheme.colorScheme.surfaceVariant,
                    1.0f to MaterialTheme.colorScheme.surface,
                ),
            )
            .litEdge(shape)
            .clickableNoRipple(interaction, onOpen),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2A1D0B))
                    .litEdge(CircleShape, topAlpha = 0.22f, bottomAlpha = 0.06f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = OrangeMid)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.projects_card_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.projects_card_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = OrangeMid,
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = TextFaint,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Projetos

@Composable
private fun ProjectsTabContent(
    projects: List<OvgProject>,
    repository: ProjectRepository,
    onOpenProject: (String) -> Unit,
    onRename: (OvgProject) -> Unit,
    onDelete: (OvgProject) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 14.dp, bottom = 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "projectsTitle") {
            Text(
                stringResource(R.string.tab_projects),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
        }
        if (projects.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
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
            }
        } else {
            items(projects, key = { it.id }) { project ->
                ProjectCard(
                    project = project,
                    repository = repository,
                    onOpen = { onOpenProject(project.id) },
                    onRename = { onRename(project) },
                    onDelete = { onDelete(project) },
                    showMenu = true,
                )
            }
        }
    }
}

/** Recent list for the Início tab (no menu — full management lives in Projetos). */
private fun LazyListScope.recentProjectItems(
    projects: List<OvgProject>,
    repository: ProjectRepository,
    onOpenProject: (String) -> Unit,
    onRename: ((OvgProject) -> Unit)?,
    onDelete: ((OvgProject) -> Unit)?,
) {
    items(projects, key = { "recent_" + it.id }) { project ->
        ProjectCard(
            project = project,
            repository = repository,
            onOpen = { onOpenProject(project.id) },
            onRename = { onRename?.invoke(project) },
            onDelete = { onDelete?.invoke(project) },
            showMenu = onRename != null,
        )
    }
}

// ------------------------------------------------------------------ cards

@Composable
private fun ProjectCard(
    project: OvgProject,
    repository: ProjectRepository,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    showMenu: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val thumbFile = remember(project.id, project.updatedAtEpochMs) {
        repository.homeThumbnailFile(project.id)
    }
    val dateText = remember(project.updatedAtEpochMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(project.updatedAtEpochMs))
    }
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressedScale = 0.98f)
            .softGlow(6.dp, shape, Color.Black)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0.0f to MaterialTheme.colorScheme.surfaceVariant,
                    1.0f to MaterialTheme.colorScheme.surface,
                ),
            )
            .litEdge(shape)
            .clickableNoRipple(interaction, onOpen),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 92.dp, height = 62.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceDim),
                contentAlignment = Alignment.Center,
            ) {
                if (thumbFile != null && thumbFile.exists()) {
                    Thumbnail(file = thumbFile)
                } else {
                    Icon(
                        Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = EdgeLight,
                        modifier = Modifier.size(22.dp),
                    )
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
                        formatDuration(ProjectMath.projectDurationMs(project)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = OrangeMid,
                )
                Text(
                    dateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showMenu) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            } else {
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = TextFaint,
                )
            }
        }
    }
}

/** Async thumbnail decode (same behaviour as the previous list). */
@Composable
private fun Thumbnail(file: File) {
    var imageBitmap by remember(file.absolutePath) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(file.absolutePath) {
        imageBitmap = withContext(Dispatchers.IO) {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
    }
    imageBitmap?.let { bmp ->
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ------------------------------------------------------------------ placebos

/** Presets / Perfil — honest placeholder screens ("Em desenvolvimento"). */
@Composable
private fun InDevelopmentTabContent(
    icon: (@Composable () -> Unit)? = null,
    profile: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .softGlow(10.dp, CircleShape, OrangeMid)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                )
                .litEdge(CircleShape, topAlpha = 0.28f, bottomAlpha = 0.08f),
            contentAlignment = Alignment.Center,
        ) {
            if (profile) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = OrangeMid,
                    modifier = Modifier.size(34.dp),
                )
            } else {
                icon?.invoke()
            }
        }
        Text(
            stringResource(R.string.in_development),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            stringResource(R.string.in_development_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// ------------------------------------------------------------------ bottom bar

/** Bottom bar: Início | Projetos | (+) | Presets | Perfil. */
@Composable
private fun OvgBottomBar(
    selected: HomeTab,
    onSelect: (HomeTab) -> Unit,
    onNewComposition: () -> Unit,
) {
    val barShape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp),
    ) {
        // Pill surface.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                .fillMaxWidth()
                .height(60.dp)
                .softGlow(10.dp, barShape, Color.Black)
                .clip(barShape)
                .background(
                    Brush.verticalGradient(
                        0.0f to MaterialTheme.colorScheme.surfaceVariant,
                        1.0f to MaterialTheme.colorScheme.surface,
                    ),
                )
                .litEdge(barShape, topAlpha = 0.26f, bottomAlpha = 0.05f),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BottomItem(
                    icon = Icons.Filled.Home,
                    label = stringResource(R.string.tab_home),
                    a11y = stringResource(R.string.a11y_tab_home),
                    selected = selected == HomeTab.HOME,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(HomeTab.HOME) },
                )
                BottomItem(
                    icon = Icons.Filled.VideoLibrary,
                    label = stringResource(R.string.tab_projects),
                    a11y = stringResource(R.string.a11y_tab_projects),
                    selected = selected == HomeTab.PROJECTS,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(HomeTab.PROJECTS) },
                )
                Spacer(Modifier.width(56.dp))
                BottomItem(
                    icon = Icons.Filled.AutoAwesome,
                    label = stringResource(R.string.tab_presets),
                    a11y = stringResource(R.string.a11y_tab_presets),
                    selected = selected == HomeTab.PRESETS,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(HomeTab.PRESETS) },
                )
                BottomItem(
                    icon = Icons.Filled.Person,
                    label = stringResource(R.string.tab_profile),
                    a11y = stringResource(R.string.a11y_tab_profile),
                    selected = selected == HomeTab.PROFILE,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(HomeTab.PROFILE) },
                )
            }
        }
        // Central (+) — glazed orange, slightly overhanging the pill.
        GlazedOrangeButton(
            onClick = onNewComposition,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = 2.dp)
                .size(52.dp),
            shape = CircleShape,
            elevation = 10.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.a11y_tab_new),
                tint = OnOrange,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun BottomItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    a11y: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (selected) OrangeMid else TextFaint
    Column(
        modifier = modifier
            .clickableNoRipple(remember { MutableInteractionSource() }, onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = a11y,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
