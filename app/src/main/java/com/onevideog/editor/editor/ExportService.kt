package com.onevideog.editor.editor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.onevideog.editor.R
import com.onevideog.editor.data.ExportResolutionEnum
import com.onevideog.editor.data.OvgProject
import com.onevideog.editor.data.ProjectRepository
import com.onevideog.editor.editor.ExportConfig.Quality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Foreground service that owns the whole export lifecycle.
 *
 * Why a foreground service: on Android 12+ a backgrounded app process is
 * frozen by the cached-app freezer (and aggressively killed by vendor
 * battery managers, e.g. realme UI on 4 GB devices). Previously the export
 * ran in a composition-scoped coroutine and was actively cancelled when the
 * export screen left composition — which is exactly why exports of about a
 * minute or longer failed while short clips succeeded. Running the
 * Transformer here keeps the process foreground while encoding, surviving
 * screen-off, navigation and backgrounding.
 *
 * State machine: QUEUED → RUNNING(pct) → FINALIZING → DONE | FAILED(reason)
 * | CANCELLED. Cancellation is user-initiated (notification action) and
 * cleans the temporary output.
 */
class ExportService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var manager: ExportManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                manager?.cancel()
                return START_NOT_STICKY
            }
            ACTION_START -> startExport(intent)
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startExport(intent: Intent) {
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID) ?: run { stopSelf(); return }
        val resolutionName = intent.getStringExtra(EXTRA_RESOLUTION)
            ?: ExportResolutionEnum.P720.name
        val qualityName = intent.getStringExtra(EXTRA_QUALITY) ?: Quality.MEDIUM.name
        val destination = intent.getStringExtra(EXTRA_DESTINATION_URI) ?: run { stopSelf(); return }

        val repository = ProjectRepository(applicationContext)
        val project: OvgProject = repository.load(projectId)?.migrated() ?: run {
            ExportBus.update(projectId, null, ExportManager.State.Failed(ExportManager.Failure.VALIDATION_FAILED))
            stopSelf()
            return
        }

        val resolution = runCatching { ExportResolutionEnum.valueOf(resolutionName) }
            .getOrDefault(ExportResolutionEnum.P720)
        val quality = runCatching { Quality.valueOf(qualityName) }.getOrDefault(Quality.MEDIUM)

        ExportBus.update(projectId, project.name, ExportManager.State.Queued)
        startForegroundWith(buildProgressNotification(0, indeterminate = true))

        val exportManager = ExportManager(applicationContext, serviceScope)
        manager = exportManager
        exportManager.onState = { state ->
            ExportBus.update(projectId, project.name, state)
            when (state) {
                is ExportManager.State.Running ->
                    updateNotification(buildProgressNotification(state.progressPercent, false))
                is ExportManager.State.Queued ->
                    updateNotification(buildProgressNotification(0, true))
                is ExportManager.State.Finalizing ->
                    updateNotification(buildFinalizingNotification())
                is ExportManager.State.Done -> {
                    updateNotification(buildResultNotification(success = true, failure = null))
                    finish()
                }
                is ExportManager.State.Failed -> {
                    updateNotification(buildResultNotification(success = false, failure = state.failure))
                    finish()
                }
                is ExportManager.State.Cancelled -> {
                    updateNotification(buildResultNotification(success = false, failure = null, cancelled = true))
                    finish()
                }
                ExportManager.State.Idle -> Unit
            }
        }

        exportManager.start(
            project = project,
            resolution = resolution,
            quality = quality,
            destinationUri = Uri.parse(destination),
        )
    }

    private fun finish() {
        manager = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        // Defensive: if the system tears us down mid-export, stop the
        // transformer and clean temp output so storage is not leaked.
        manager?.cancel()
        manager = null
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------- notifications

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.export_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildProgressNotification(percent: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.export_notification_title))
            .setContentText(getString(R.string.export_running, percent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(100, percent, indeterminate)
            .setContentIntent(contentIntent())
            .addAction(0, getString(R.string.cancel), cancelIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun buildFinalizingNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.export_notification_title))
            .setContentText(getString(R.string.export_finalizing))
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setContentIntent(contentIntent())
            .build()

    private fun buildResultNotification(
        success: Boolean,
        failure: ExportManager.Failure?,
        cancelled: Boolean = false,
    ): Notification {
        val text = when {
            cancelled -> getString(R.string.export_canceled)
            success -> getString(R.string.export_done_body)
            else -> failureString(failure)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_error,
            )
            .setContentTitle(
                getString(
                    if (success) R.string.export_done_title else R.string.export_failed_title,
                ),
            )
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
    }

    private fun failureString(failure: ExportManager.Failure?): String = getString(
        when (failure) {
            ExportManager.Failure.INSUFFICIENT_STORAGE -> R.string.error_storage
            ExportManager.Failure.IO_READ -> R.string.error_read_source
            ExportManager.Failure.IO_WRITE -> R.string.error_write_output
            ExportManager.Failure.ENCODING -> R.string.error_encoding
            ExportManager.Failure.DECODING -> R.string.error_decoding
            ExportManager.Failure.MUXING -> R.string.error_muxing
            ExportManager.Failure.EXPORT_ERROR -> R.string.error_export_failed
            ExportManager.Failure.COPY_ERROR -> R.string.error_file_unreadable
            ExportManager.Failure.VALIDATION_FAILED -> R.string.error_no_video
            null, ExportManager.Failure.GENERIC -> R.string.error_generic
        },
    )

    private fun startForegroundWith(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun updateNotification(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "export"
        private const val NOTIFICATION_ID = 41

        const val ACTION_START = "com.onevideog.editor.export.START"
        const val ACTION_CANCEL = "com.onevideog.editor.export.CANCEL"
        const val EXTRA_PROJECT_ID = "projectId"
        const val EXTRA_RESOLUTION = "resolution"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_DESTINATION_URI = "destinationUri"

        fun startIntent(
            context: Context,
            projectId: String,
            resolution: ExportResolutionEnum,
            quality: Quality,
            destinationUri: Uri,
        ): Intent = Intent(context, ExportService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_PROJECT_ID, projectId)
            .putExtra(EXTRA_RESOLUTION, resolution.name)
            .putExtra(EXTRA_QUALITY, quality.name)
            .putExtra(EXTRA_DESTINATION_URI, destinationUri.toString())

        fun cancelIntent(context: Context): Intent =
            Intent(context, ExportService::class.java).setAction(ACTION_CANCEL)
    }
}
