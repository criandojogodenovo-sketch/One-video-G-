package com.onevideog.editor

import android.app.Application
import com.onevideog.editor.diag.DiagnosticsInstaller
import com.onevideog.editor.diag.DiagnosticsLogger

/**
 * One Video G application class.
 *
 * The app is offline-first: no account, no network calls, no telemetry.
 *
 * v0.2.3 (temporary diagnostics): initializes the opt-in, on-device-only
 * diagnostics logger and the crash record hook. Nothing is sent anywhere;
 * logs stay in app-private storage until the user copies/shares them.
 */
class OvgApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DiagnosticsLogger.init(this)
        DiagnosticsInstaller.install()
    }
}
