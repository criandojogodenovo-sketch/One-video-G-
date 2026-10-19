package com.onevideog.editor.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.onevideog.editor.BuildConfig
import com.onevideog.editor.R
import com.onevideog.editor.data.ExportResolutionEnum
import com.onevideog.editor.data.LanguageMode
import com.onevideog.editor.data.SettingsRepository
import com.onevideog.editor.data.ThemeMode
import com.onevideog.editor.ui.common.OvgTopBar

/**
 * Settings: language (pt/en/system, applied via AppCompat per-app locales and
 * persisted), theme (also applied to the AppCompat delegate so XML-level
 * surfaces match), default export resolution (pre-selected on the export
 * screen), about.
 *
 * Every setting here has a real effect:
 *  - Language   -> AppCompatDelegate.setApplicationLocales (persisted)
 *  - Theme      -> Compose color scheme + AppCompat day/night (window/dialogs)
 *  - Resolution -> initial selection on the Export screen
 */
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onBack: () -> Unit,
    onLocaleChanged: (LanguageMode) -> Unit,
) {
    val theme by settings.theme.collectAsState()
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.language),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            LanguageRow(
                label = stringResource(R.string.language_system),
                selected = language == LanguageMode.SYSTEM,
            ) {
                settings.setLanguage(LanguageMode.SYSTEM)
                onLocaleChanged(LanguageMode.SYSTEM)
            }
            LanguageRow(
                label = stringResource(R.string.portuguese),
                selected = language == LanguageMode.PT,
            ) {
                settings.setLanguage(LanguageMode.PT)
                onLocaleChanged(LanguageMode.PT)
            }
            LanguageRow(
                label = stringResource(R.string.english),
                selected = language == LanguageMode.EN,
            ) {
                settings.setLanguage(LanguageMode.EN)
                onLocaleChanged(LanguageMode.EN)
            }

            Text(
                stringResource(R.string.theme),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 12.dp),
            )
            RadioRow(
                label = stringResource(R.string.theme_dark),
                selected = theme == ThemeMode.DARK,
            ) { settings.setTheme(ThemeMode.DARK) }
            RadioRow(
                label = stringResource(R.string.theme_light),
                selected = theme == ThemeMode.LIGHT,
            ) { settings.setTheme(ThemeMode.LIGHT) }
            RadioRow(
                label = stringResource(R.string.theme_system),
                selected = theme == ThemeMode.SYSTEM,
            ) { settings.setTheme(ThemeMode.SYSTEM) }

            Text(
                stringResource(R.string.export_default_resolution),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportResolutionEnum.entries.forEach { res ->
                    androidx.compose.material3.FilterChip(
                        selected = defaultRes == res,
                        onClick = { settings.setDefaultResolution(res) },
                        label = { Text(stringResource(res.labelRes)) },
                    )
                }
            }
            Text(
                stringResource(R.string.export_default_resolution_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                stringResource(R.string.about),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                stringResource(R.string.app_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LanguageRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    RadioRow(label, selected, onSelect)
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
