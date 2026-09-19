package com.ayati.noveldownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

class DownloadService : Service() {

    companion object {
        const val EXTRA_URL = "url"
        const val ACTION_CANCEL = "com.ayati.noveldownloader.action.CANCEL"
        private const val CHANNEL_ID = "download"
        private const val NOTIF_ID_PROGRESS = 1
        private const val NOTIF_ID_RESULT = 2
        private const val SUBDIR = "小説ダウンローダー"
    }

    @Volatile
    private var running = false
    private var lastNotified = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_CANCEL -> {
                thread { PyBridge.module.callAttr("cancel") }
            }
            intent?.getStringExtra(EXTRA_URL) != null && !running -> {
                running = true
                val notif = buildProgressNotification(getString(R.string.notif_preparing), 0, 0)
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID_PROGRESS, notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIF_ID_PROGRESS, notif)
                }
                val url = intent.getStringExtra(EXTRA_URL)!!
                thread { work(url) }
            }
        }
        return START_NOT_STICKY
    }

    private fun work(url: String) {
        DownloadState.reset()
        DownloadState.ui.value = DownloadState.Ui(phase = DownloadState.Phase.PREPARING)

        val staging = File(filesDir, "staging")
        staging.deleteRecursively()
        staging.mkdirs()

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val saveTxt = prefs.getBoolean("save_txt", false)

        val listener = Listener()
        val code = try {
            PyBridge.ensureStarted(applicationContext)
            val opts = JSONObject()
                .put("output_dir", staging.path)
                .put("horizontal", prefs.getBoolean("horizontal", false))
                .put("kobo", prefs.getBoolean("kobo", false))
                .put("use_site_cover", prefs.getBoolean("use_site_cover", false))
            PyBridge.module.callAttr("run", url, opts.toString(), listener).toInt()
        } catch (e: Exception) {
            DownloadState.appendLog(getString(R.string.log_app_error, e.toString()))
            1
        }

        when (code) {
            0 -> {
                val saved = staging.listFiles { f ->
                    f.name.endsWith(".epub") || (saveTxt && f.name.endsWith(".txt"))
                }.orEmpty()
                    .sortedBy { !it.name.endsWith(".epub") }
                    .mapNotNull { saveToDownloads(it) }
                if (saved.isEmpty()) {
                    DownloadState.appendLog(getString(R.string.log_no_epub))
                    finish(DownloadState.Phase.ERROR, getString(R.string.status_failed))
                } else {
                    DownloadState.ui.value = DownloadState.ui.value.copy(savedFiles = saved)
                    finish(DownloadState.Phase.DONE,
                        getString(R.string.status_done, saved.joinToString { it.name }))
                }
            }
            130 -> finish(DownloadState.Phase.CANCELLED, getString(R.string.status_cancelled))
            else -> finish(DownloadState.Phase.ERROR, getString(R.string.status_failed))
        }

        staging.deleteRecursively()
        running = false
        stopSelf()
    }

    private fun finish(phase: DownloadState.Phase, message: String) {
        DownloadState.ui.value = DownloadState.ui.value.copy(phase = phase, statusLine = message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID_RESULT, notif)
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    inner class Listener {
        fun onLine(text: String) {
            DownloadState.appendLog(text)
            if (text.isNotBlank()) {
                DownloadState.ui.value =
                    DownloadState.ui.value.copy(statusLine = text.trim())
            }
        }

        fun onProgress(n: Int, total: Int) {
            DownloadState.ui.value = DownloadState.ui.value.copy(
                phase = DownloadState.Phase.DOWNLOADING, n = n, total = total)
            val now = System.currentTimeMillis()
            if (now - lastNotified > 900) {
                lastNotified = now
                getSystemService(NotificationManager::class.java).notify(
                    NOTIF_ID_PROGRESS,
                    buildProgressNotification(getString(R.string.progress_chapters, n, total), n, total))
            }
        }

        fun onPhase(phase: String) {
            val p = when (phase) {
                "PREPARING" -> DownloadState.Phase.PREPARING
                "DOWNLOADING" -> DownloadState.Phase.DOWNLOADING
                "SAVING" -> DownloadState.Phase.SAVING
                else -> return
            }
            DownloadState.ui.value = DownloadState.ui.value.copy(phase = p)
        }
    }

    private fun buildProgressNotification(text: String, n: Int, total: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(if (total > 0) total else 0, n, total <= 0)
            .build()

    private fun saveToDownloads(file: File): DownloadState.SavedFile? {
        val mime = when {
            file.name.endsWith(".epub") -> "application/epub+zip"
            file.name.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR)
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: return null
                DownloadState.SavedFile(file.name, uri.toString(), mime)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), SUBDIR)
                dir.mkdirs()
                var dst = File(dir, file.name)
                var i = 1
                while (dst.exists()) {
                    dst = File(dir, "${file.nameWithoutExtension} ($i).${file.extension}")
                    i++
                }
                file.copyTo(dst)
                MediaScannerConnection.scanFile(this, arrayOf(dst.path), null, null)
                val uri = FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", dst)
                DownloadState.SavedFile(dst.name, uri.toString(), mime)
            }
        } catch (e: Exception) {
            DownloadState.appendLog(getString(R.string.log_save_fail, file.name, e.toString()))
            null
        }
    }
}
