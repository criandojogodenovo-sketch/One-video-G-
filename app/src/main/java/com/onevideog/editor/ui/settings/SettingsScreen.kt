package com.onevideog.editor.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.BuildConfig
import com.onevideog.editor.R
import com.onevideog.editor.data.ExportResolutionEnum
import com.onevideog.editor.data.LanguageMode
import com.onevideog.editor.data.SettingsRepository
import com.onevideog.editor.ui.common.OvgFilterChip
import com.onevideog.editor.ui.common.OvgRadioRow
import com.onevideog.editor.ui.common.OvgSectionCard
import com.onevideog.editor.ui.common.OvgTopBar

/**
 * Settings: language (pt/en/system, applied via AppCompat per-app locales and
 * persisted), default export resolution (pre-selected on the export screen),
 * about.
 *
 * v0.9.3: the whole screen uses the shared orange/graphite kit (section
 * cards + orange radio rows + orange chips) — no generic background, no
 * Material 3 default (purple) radios. The THEME section is gone: the app
 * identity is the dark glazed-orange canvas (OvgTheme is always dark), so a
 * light-theme selector would only promise something the identity cannot
 * deliver. Every setting here still has a real effect:
 *  - Language   -> AppCompatDelegate.setApplicationLocales (persisted)
 *  - Resolution -> initial selection on the Export screen
 */
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
    onLocaleChanged: (LanguageMode) -> Unit,
) {
    val language by settings.language.collectAsState()
    val defaultRes by settings.defaultResolution.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        OvgTopBar(title = stringResource(R.string.settings), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.language),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            OvgSectionCard {
                OvgRadioRow(
                    label = stringResource(R.string.language_system),
                    selected = language == LanguageMode.SYSTEM,
                ) {
                    settings.setLanguage(LanguageMode.SYSTEM)
                    onLocaleChanged(LanguageMode.SYSTEM)
                }
                OvgRadioRow(
                    label = stringResource(R.string.portuguese),
                    selected = language == LanguageMode.PT,
                ) {
                    settings.setLanguage(LanguageMode.PT)
                    onLocaleChanged(LanguageMode.PT)
                }
                OvgRadioRow(
                    label = stringResource(R.string.english),
                    selected = language == LanguageMode.EN,
                ) {
                    settings.setLanguage(LanguageMode.EN)
                    onLocaleChanged(LanguageMode.EN)
                }
            }

            Text(
                stringResource(R.string.export_default_resolution),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            OvgSectionCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ExportResolutionEnum.entries.forEach { res ->
                        OvgFilterChip(
                            selected = defaultRes == res,
                            onClick = { settings.setDefaultResolution(res) },
                            label = stringResource(res.labelRes),
                        )
                    }
                }
                Text(
                    stringResource(R.string.export_default_resolution_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // Temporary diagnostics (v0.2.3): opt-in, on-device evidence
            // collection for field debugging (toggle/copy/clear/export).
            DiagnosticsSection(settings = settings)

            Text(
                stringResource(R.string.about),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            OvgSectionCard {
                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                    Text(
                        stringResource(R.string.app_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
