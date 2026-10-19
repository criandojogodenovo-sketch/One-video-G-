package com.onevideog.editor.data

import android.content.Context
import android.content.SharedPreferences
import com.onevideog.editor.R
import com.onevideog.editor.diag.DiagnosticsLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Theme options available in Settings. */
enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** Language options for the in-app language selector. */
enum class LanguageMode { SYSTEM, PT, EN }

/**
 * Small SharedPreferences-backed settings store.
 *
 * The language choice is applied through AppCompat per-app locales
 * (persisted by AppLocalesMetadataHolderService); the value here only drives
 * the UI selection state.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("one_video_g_settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(themeFromName(prefs.getString(KEY_THEME, null)))
    val theme: StateFlow<ThemeMode> get() = _theme

    private val _language = MutableStateFlow(languageFromName(prefs.getString(KEY_LANGUAGE, null)))
    val language: StateFlow<LanguageMode> get() = _language

    private val _defaultResolution =
        MutableStateFlow(resFromName(prefs.getString(KEY_EXPORT_RES, null)))
    val defaultResolution: StateFlow<ExportResolutionEnum> get() = _defaultResolution

    /**
     * Temporary diagnostics (v0.2.3): mirrors [DiagnosticsLogger.enabled].
     * OFF by default; the toggle persists via the logger (same prefs file)
     * so the state is available before this repository is constructed.
     */
    val diagnosticsEnabled: StateFlow<Boolean> get() = DiagnosticsLogger.enabled

    fun setDiagnosticsEnabled(value: Boolean) {
        DiagnosticsLogger.setEnabled(value)
    }

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _theme.value = mode
    }

    fun setLanguage(mode: LanguageMode) {
        prefs.edit().putString(KEY_LANGUAGE, mode.name).apply()
        _language.value = mode
    }

    fun setDefaultResolution(value: ExportResolutionEnum) {
        prefs.edit().putString(KEY_EXPORT_RES, value.name).apply()
        _defaultResolution.value = value
    }

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_EXPORT_RES = "export_resolution"

        fun themeFromName(name: String?): ThemeMode =
            runCatching { ThemeMode.valueOf(name ?: "") }.getOrDefault(ThemeMode.DARK)

        fun languageFromName(name: String?): LanguageMode =
            runCatching { LanguageMode.valueOf(name ?: "") }.getOrDefault(LanguageMode.SYSTEM)

        fun resFromName(name: String?): ExportResolutionEnum =
            runCatching { ExportResolutionEnum.valueOf(name ?: "") }
                .getOrDefault(ExportResolutionEnum.P720)
    }
}

/** Export resolution presets. Kept in `data` so settings and export share it. */
enum class ExportResolutionEnum(val targetHeight: Int, val labelRes: Int) {
    P720(720, R.string.res_720),
    P1080(1080, R.string.res_1080),
}
