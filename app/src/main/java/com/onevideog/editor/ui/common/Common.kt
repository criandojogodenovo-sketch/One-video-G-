package com.onevideog.editor.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.onevideog.editor.R
import com.onevideog.editor.ui.theme.OnOrange
import com.onevideog.editor.ui.theme.litEdge

/**
 * v0.9.2 common UI — restyled for the glazed-orange/graphite identity.
 * Same contracts and behaviours as before; only looks and motion changed.
 */

/** Dialog content wrapper: entrance = scale + fade (220 ms, draw phase only). */
@Composable
private fun AnimatedDialogContent(
    content: @Composable () -> Unit,
) {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.90f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "dialogScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "dialogAlpha",
    )
    Surface(
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        content()
    }
}

/** Standard top bar — transparent over the deep-black canvas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OvgTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            )
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.a11y_back),
                    )
                }
            }
        },
        actions = actions,
    )
}

/** Empty state used by the home list and the editor timeline. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = Color.Transparent) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Simple text-input dialog (used for new project / rename). */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    hint: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        AnimatedDialogContent {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(hint) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(6.dp))
                    GlazedDialogButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }, text = confirmLabel)
                }
            }
        }
    }
}

/** Small glazed-orange confirm action inside dialogs. */
@Composable
private fun GlazedDialogButton(onClick: () -> Unit, text: String) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = OnOrange,
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 4.dp,
            pressedElevation = 8.dp,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Simple confirm dialog (used for delete confirmation). */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        AnimatedDialogContent {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = onConfirm,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------ v0.9.3 shared kit
// The one styled vocabulary for every screen: card section, radio row,
// switch row, filter chip and alert dialog. All of them read the complete
// orange/graphite colorScheme — no screen falls back to the Material 3
// default (purple) look anymore.

/** Graphite section card used by Settings/Export groups. */
@Composable
fun OvgSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0.0f to MaterialTheme.colorScheme.surfaceVariant,
                    1.0f to MaterialTheme.colorScheme.surface,
                ),
            )
            .litEdge(shape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        content = content,
    )
}

/** Selection row with an orange radio — Settings' one list-row style. */
@Composable
fun OvgRadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Toggle row with a Material 3 Switch — inherits the orange track colors. */
@Composable
fun OvgSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** The one FilterChip style — orange selection on graphite. */
@Composable
fun OvgFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OvgFilterChip(selected = selected, onClick = onClick, modifier = modifier) {
        Text(label)
    }
}

/**
 * The one FilterChip style — composable-label overload with the standard
 * M3 argument shape, so screens/sheets switch to the themed chip through an
 * aliased import (`import com.onevideog.editor.ui.common.OvgFilterChip as
 * FilterChip`) without touching any call site.
 */
@Composable
fun OvgFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}

/**
 * The one alert-dialog style — graphite surface, glazed-orange confirm,
 * entrance scale+fade (the AnimatedDialogContent motion). Body and buttons
 * stay call-site owned.
 */
@Composable
fun OvgAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    destructiveConfirm: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title) },
        text = if (body != null) {
            { Text(body) }
        } else {
            null
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(14.dp),
                colors = if (destructiveConfirm) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                },
            ) {
                Text(confirmLabel, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = if (dismissLabel != null && onDismiss != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(dismissLabel)
                }
            }
        } else {
            null
        },
    )
}
