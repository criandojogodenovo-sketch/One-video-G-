package com.onevideog.editor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import com.onevideog.editor.ui.common.OvgFilterChip as FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.R
import com.onevideog.editor.data.Clip
import com.onevideog.editor.data.ClipTransition
import com.onevideog.editor.data.EffectKind
import com.onevideog.editor.data.TransitionKind
import com.onevideog.editor.editor.ClipEffectsFactory
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.editor.TransitionPlanner

/**
 * v0.6.0 sheets for the Fase H transitions and the Fase I per-clip effects.
 *
 * Both sheets only WRITE through the ViewModel (one commit per change, one
 * undo step); the preview and the export derive everything from the committed
 * project state through the shared effect pipeline — no sheet-level rendering
 * decisions exist.
 */

// ---------------------------------------------------------------- Transition

/**
 * H5 — transition editor for ONE adjacent pair. Opened from the timeline
 * badge (⚡ / + between two clip blocks). Shows the five kinds + "None",
 * and a duration slider clamped to the legal window
 * ([TransitionPlanner.maxLegalDurationMs] — whatever this sheet commits is
 * always valid, so the planner never has to reject a user edit).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransitionSheet(
    viewModel: EditorViewModel,
    clipIdA: String,
    clipIdB: String,
    onDismiss: () -> Unit,
) {
    val state by viewModel.ui.collectAsState()
    val project = state.project ?: return
    val clipA = project.clips.firstOrNull { it.id == clipIdA }
    val clipB = project.clips.firstOrNull { it.id == clipIdB }
    val existing = project.transitions.firstOrNull {
        it.betweenClipIds.first == clipIdA && it.betweenClipIds.second == clipIdB
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SheetTitle(stringResource(R.string.transitions_title))
            val legalMax = if (clipA != null && clipB != null) {
                TransitionPlanner.maxLegalDurationMs(clipA, clipB)
            } else {
                0L
            }
            if (clipA == null || clipB == null || legalMax < TransitionPlanner.MIN_DURATION_MS) {
                InfoLine(
                    stringResource(R.string.trans_note),
                )
                OutlinedButton(
                    onClick = {
                        viewModel.clearTransition(clipIdA, clipIdB)
                        onDismiss()
                    },
                    enabled = existing != null,
                ) {
                    Text(stringResource(R.string.trans_remove))
                }
                return@Column
            }

            // Kind chips: None + the five kinds. "None" removes the transition.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = existing == null,
                    onClick = {
                        viewModel.clearTransition(clipIdA, clipIdB)
                    },
                    label = { Text(stringResource(R.string.transition_none)) },
                )
            }
            TransitionKind.entries.chunked(2).forEach { rowKinds ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowKinds.forEach { kind ->
                        FilterChip(
                            selected = existing?.kind == kind,
                            onClick = {
                                viewModel.setTransition(clipIdA, clipIdB, kind, legalMax.coerceAtMost(existing?.durationMs ?: 500L))
                            },
                            label = { Text(transitionLabel(kind)) },
                        )
                    }
                }
            }

            // Duration slider — only meaningful with an active transition;
            // snapped to 100 ms (the value range starts at the minimum).
            if (existing != null) {
                var sliderValue by remember(
                    existing.id, existing.kind, existing.durationMs, legalMax,
                ) { mutableLongStateOf(existing.durationMs) }
                Text(
                    stringResource(R.string.trans_duration, sliderValue),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Slider(
                    value = sliderValue.toFloat(),
                    onValueChange = { sliderValue = (it.toLong() / 100L) * 100L },
                    onValueChangeFinished = {
                        viewModel.setTransition(
                            clipIdA, clipIdB, existing.kind,
                            sliderValue.coerceIn(TransitionPlanner.MIN_DURATION_MS, legalMax),
                        )
                    },
                    valueRange = TransitionPlanner.MIN_DURATION_MS.toFloat()..legalMax.toFloat(),
                )
            }
            Text(
                stringResource(R.string.trans_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun transitionLabel(kind: TransitionKind): String = stringResource(
    when (kind) {
        TransitionKind.CROSSFADE -> R.string.trans_kind_crossfade
        TransitionKind.WIPE_LEFT -> R.string.trans_kind_wipe_left
        TransitionKind.WIPE_RIGHT -> R.string.trans_kind_wipe_right
        TransitionKind.SLIDE_UP -> R.string.trans_kind_slide_up
        TransitionKind.SLIDE_DOWN -> R.string.trans_kind_slide_down
    },
)

// ------------------------------------------------------------- Clip effects

/**
 * I6/I7 — per-clip 2D effects for the selected (or playhead) video clip.
 * One toggle + one intensity slider per kind; the 3-effects limit is shown
 * as a warning and enforced again in the ViewModel (defence in depth).
 */
@Composable
internal fun ClipEffectsPanel(viewModel: EditorViewModel, clip: Clip) {
    val state by viewModel.ui.collectAsState()
    // Always read the FRESH clip from state (slider commits replace it).
    val fresh = state.project?.clips?.firstOrNull { it.id == clip.id } ?: clip
    SheetTitle(stringResource(R.string.effects_title))
    InfoLine(stringResource(R.string.effects_none_hint))
    val atLimit = fresh.effects.size >= ClipEffectsFactory.MAX_EFFECTS_PER_CLIP
    if (atLimit) {
        Text(
            stringResource(R.string.effects_limit_reached, ClipEffectsFactory.MAX_EFFECTS_PER_CLIP),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    EffectKind.entries.forEach { kind ->
        val active = fresh.effects.firstOrNull { it.kind == kind }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = active != null,
                onClick = {
                    if (active != null) {
                        viewModel.removeClipEffect(fresh.id, kind)
                    } else {
                        viewModel.setClipEffect(fresh.id, kind, 0.5f)
                    }
                },
                // I7: the 4th effect cannot be enabled from the UI either.
                enabled = active != null || !atLimit,
                label = { Text(effectLabel(kind)) },
            )
        }
        if (active != null) {
            var intensity by remember(fresh.id, kind, active.intensity) {
                mutableFloatStateOf(active.intensity)
            }
            Text(
                stringResource(R.string.effect_intensity, (intensity * 100).toInt()),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Slider(
                value = intensity,
                onValueChange = { intensity = (it * 10f).toInt() / 10f },
                onValueChangeFinished = {
                    viewModel.setClipEffect(fresh.id, kind, intensity)
                },
                valueRange = 0f..1f,
                steps = 9,
            )
        }
    }
    Text(
        stringResource(R.string.effects_limit_note, ClipEffectsFactory.MAX_EFFECTS_PER_CLIP),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun effectLabel(kind: EffectKind): String = stringResource(
    when (kind) {
        EffectKind.BLUR -> R.string.effect_blur
        EffectKind.VIGNETTE -> R.string.effect_vignette
        EffectKind.SHARPEN -> R.string.effect_sharpen
    },
)
