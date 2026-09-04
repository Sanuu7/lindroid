package dev.lindroid.app.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.StatFs
import androidx.core.app.NotificationCompat
import dev.lindroid.app.MainActivity
import dev.lindroid.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DesktopSetupStatus {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    FAILED,
}

object DesktopSetupBus {
    private const val MAX_LOG = 100_000
    private val buffer = StringBuilder()
    private val mutableStatus = MutableStateFlow(DesktopSetupStatus.NOT_INSTALLED)
    private val mutableLog = MutableStateFlow("")
    private val mutableError = MutableStateFlow<String?>(null)

    val status = mutableStatus.asStateFlow()
    val log = mutableLog.asStateFlow()
    val error = mutableError.asStateFlow()

    fun refresh(context: Context) {
        if (mutableStatus.value == DesktopSetupStatus.INSTALLING) return
        mutableStatus.value = if (RuntimePaths(context).rootfs.resolve(DESKTOP_MARKER).isFile) {
            DesktopSetupStatus.INSTALLED
        } else {
            DesktopSetupStatus.NOT_INSTALLED
        }
    }

    internal fun status(value: DesktopSetupStatus, error: String? = null) {
        mutableStatus.value = value
        mutableError.value = error
    }

    internal fun append(text: String) = synchronized(buffer) {
        buffer.append(text)
        if (buffer.length > MAX_LOG) buffer.delete(0, buffer.length - MAX_LOG)
        mutableLog.value = buffer.toString()
    }

    internal const val DESKTOP_MARKER = "root/.lindroid-desktop"
}

class DesktopSetupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (process?.isAlive != true) installDesktop()
        return START_NOT_STICKY
    }

    private fun installDesktop() {
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Installing the Debian desktop…"))
        DesktopSetupBus.status(DesktopSetupStatus.INSTALLING)
        DesktopSetupBus.append("Preparing the XFCE desktop. This is a one-time download.\n")

        scope.launch {
            try {
                ensureFreeSpace()
                val command = ProotRuntime.cleanEnvironment(
                    listOf("/bin/bash", "-lc", SETUP_SCRIPT),
                )
                val running = ProotRuntime.processBuilder(this@DesktopSetupService, command).start()
                process = running
                running.inputStream.reader().useLines { lines ->
                    lines.forEach { DesktopSetupBus.append("$it\n") }
                }
                val exit = running.waitFor()
                if (exit == 0 && RuntimePaths(this@DesktopSetupService).rootfs.resolve(DesktopSetupBus.DESKTOP_MARKER).isFile) {
                    DesktopSetupBus.append("Desktop installation complete.\n")
                    DesktopSetupBus.status(DesktopSetupStatus.INSTALLED)
                } else {
                    error("APT exited with code $exit")
                }
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                DesktopSetupBus.append("Desktop setup failed: $message\n")
                DesktopSetupBus.status(DesktopSetupStatus.FAILED, message)
            } finally {
                process = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * The XFCE download needs roughly 1.2 GB free: APT archives plus the
     * unpacked packages inside the rootfs.
     */
    private fun ensureFreeSpace() {
        val available = StatFs(RuntimePaths(this).rootfs.path).availableBytes
        check(available >= 1_200_000_000L) {
            val gigabytes = available / (1024f * 1024f * 1024f)
            "Not enough free space: the desktop needs about 1.2 GB and only %.1f GB is free".format(gigabytes)
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Desktop setup", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Downloads and configures XFCE inside Debian"
            },
        )
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_terminal_notification)
        .setContentTitle("Lindroid desktop")
        .setContentText(text)
        .setOngoing(true)
        .setProgress(0, 0, true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    override fun onDestroy() {
        process?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "lindroid_desktop_setup"
        private const val NOTIFICATION_ID = 1202
        private const val SETUP_SCRIPT = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y --no-install-recommends xfce4 xfce4-terminal dbus-x11 tigervnc-standalone-server tigervnc-tools xfonts-base fonts-dejavu-core adwaita-icon-theme
            apt-get clean
            rm -rf /var/lib/apt/lists/*
            mkdir -p /root/.config/xfce4 /root/Desktop
            touch /root/.lindroid-desktop
        """

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DesktopSetupService::class.java))
        }
    }
}
