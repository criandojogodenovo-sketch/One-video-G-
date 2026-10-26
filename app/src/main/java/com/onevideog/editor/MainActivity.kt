package com.onevideog.editor

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.onevideog.editor.data.LanguageMode
import com.onevideog.editor.data.SettingsRepository
import com.onevideog.editor.editor.EditorViewModel
import com.onevideog.editor.ui.editor.EditorScreen
import com.onevideog.editor.ui.export.ExportScreen
import com.onevideog.editor.ui.home.ProjectsScreen
import com.onevideog.editor.ui.settings.SettingsScreen
import com.onevideog.editor.ui.theme.OvgTheme

/**
 * Single-activity Compose app. Extends AppCompatActivity so that per-app
 * locales (AppCompatDelegate) work down to API 24 with autoStoreLocales.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)
        applyLocale(settings.language.value)

        setContent {
            // v0.9.3: the identity is DARK — the scheme no longer follows the
            // system light mode (light onSurface text over the hard-coded
            // deep-black home canvas was the device report's unreadable
            // "textos escuros sobre fundo escuro"). The AppCompat delegate
            // stays in night mode so XML-level surfaces (window background,
            // dialogs) match the Compose scheme.
            androidx.compose.runtime.LaunchedEffect(Unit) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
            OvgTheme {
                OvgNavHost(
                    settings = settings,
                    applyLocale = { mode -> applyLocale(mode) },
                )
            }
        }
    }

    fun applyLocale(mode: LanguageMode) {
        val locales = when (mode) {
            LanguageMode.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            LanguageMode.PT -> LocaleListCompat.forLanguageTags("pt")
            LanguageMode.EN -> LocaleListCompat.forLanguageTags("en")
        }
        if (AppCompatDelegate.getApplicationLocales() != locales) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}

/** Access to the settings repository from composables. */
val LocalSettings = staticCompositionLocalOf<SettingsRepository> {
    error("SettingsRepository not provided")
}

@Composable
private fun OvgNavHost(
    settings: SettingsRepository,
    applyLocale: (LanguageMode) -> Unit,
) {
    val navController = rememberNavController()
    val editorViewModel: EditorViewModel = viewModel()

    // v0.9.2: smooth screen transitions — fade + short slide, 200-350 ms,
    // GPU-friendly (no shared-element motion, no blur).
    NavHost(
        navController = navController,
        startDestination = "home",
        enterTransition = {
            fadeIn(tween(280)) + slideInVertically(tween(280)) { it / 22 }
        },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = {
            fadeIn(tween(280)) + slideInVertically(tween(280)) { -it / 22 }
        },
        popExitTransition = { fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 22 } },
    ) {
        composable("home") {
            ProjectsScreen(
                onOpenProject = { id -> navController.navigate("editor/$id") },
                onOpenSettings = { navController.navigate("settings") },
                viewModel = editorViewModel,
            )
        }
        composable(
            route = "editor/{projectId}",
            arguments = listOf(navArgument("projectId") { }),
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            EditorScreen(
                projectId = projectId,
                viewModel = editorViewModel,
                onBack = { navController.popBackStack() },
                onExport = { id -> navController.navigate("export/$id") },
            )
        }
        composable(
            route = "export/{projectId}",
            arguments = listOf(navArgument("projectId") { }),
        ) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            ExportScreen(
                projectId = projectId,
                viewModel = editorViewModel,
                settings = settings,
                onBack = { navController.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(
                settings = settings,
                onBack = { navController.popBackStack() },
                onLocaleChanged = { mode -> applyLocale(mode) },
            )
        }
    }
}
