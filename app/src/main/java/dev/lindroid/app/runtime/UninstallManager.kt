package dev.lindroid.app.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.lindroid.app.MainActivity
import dev.lindroid.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class UninstallTarget {
    DESKTOP,
    DEBIAN,
}

object UninstallBus {
    private val mutableRunning = MutableStateFlow(false)
    private val mutableMessage = MutableStateFlow<String?>(null)
    private val mutableCompleted = MutableStateFlow(0)

    val running = mutableRunning.asStateFlow()
    val message = mutableMessage.asStateFlow()
    val completed = mutableCompleted.asStateFlow()

    internal fun setRunning(value: Boolean, message: String? = null) {
        mutableRunning.value = value
        mutableMessage.value = message
    }

    internal fun notifyCompleted() {
        mutableCompleted.value += 1
    }
}

object UninstallManager {
    internal const val EXTRA_TARGET = "target"
    internal const val EXTRA_CONTAINER = "container"

    fun uninstall(context: Context, target: UninstallTarget, containerId: String = RuntimePaths.DEFAULT_ID) {
        if (UninstallBus.running.value) return
        UninstallBus.setRunning(true)
        context.startForegroundService(
            Intent(context, UninstallService::class.java)
                .putExtra(EXTRA_TARGET, target.name)
                .putExtra(EXTRA_CONTAINER, containerId),
        )
    }
}

class UninstallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val target = intent?.getStringExtra(UninstallManager.EXTRA_TARGET)
            ?.let { name -> runCatching { UninstallTarget.valueOf(name) }.getOrNull() }
        if (target == null) {
            UninstallBus.setRunning(false, null)
            stopSelf()
            return START_NOT_STICKY
        }
        val containerId = intent.getStringExtra(UninstallManager.EXTRA_CONTAINER) ?: RuntimePaths.DEFAULT_ID

        createChannel()
        startForeground(
            NOTIFICATION_ID,
            notification(
                if (target == UninstallTarget.DEBIAN) "Removing the container and its files…" else "Removing the desktop…",
            ),
        )

        scope.launch {
            try {
                when (target) {
                    UninstallTarget.DEBIAN -> removeContainer(containerId)
                    UninstallTarget.DESKTOP -> removeDesktop(containerId)
                }
                UninstallBus.setRunning(false, "Removal complete")
                UninstallBus.notifyCompleted()
            } catch (error: Throwable) {
                UninstallBus.setRunning(false, error.message ?: error.javaClass.simpleName)
                UninstallBus.notifyCompleted()
            } finally {
                process = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun removeContainer(containerId: String) {
        LinuxSessionService.stop(this)
        DesktopSessionService.stop(this)
        // Give the foreground services a moment to tear their PRoot processes down.
        delay(800)
        val paths = RuntimePaths(this, containerId)
        paths.rootfs.deleteRecursively()
        paths.installStage.deleteRecursively()
        check(!paths.rootfs.exists()) { "Could not fully delete the container files" }
        ContainerRegistry.remove(this, containerId)
    }

    private suspend fun removeDesktop(containerId: String) {
        val paths = RuntimePaths(this, containerId)
        check(paths.marker.isFile) { "This container is not installed" }
        val container = ContainerRegistry.find(this, containerId) ?: return
        DesktopSessionService.stop(this)
        val command = ProotRuntime.cleanEnvironment(
            command = listOf("/bin/bash", "-lc", desktopRemoveScript(container.flavor)),
        )
        val running = ProotRuntime.processBuilder(this, command, containerId).start()
        process = running
        running.inputStream.reader().useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank()) UninstallBus.setRunning(true, line.take(160))
            }
        }
        val exit = running.waitFor()
        check(exit == 0) { "APT exited with code $exit" }
        check(!paths.rootfs.resolve(DesktopSetupBus.DESKTOP_MARKER).isFile) {
            "The desktop files could not be removed"
        }
    }

    private fun desktopRemoveScript(flavor: DistroFlavor): String = when (flavor) {
        DistroFlavor.DEBIAN -> """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            apt-get purge -y --auto-remove xfce4 xfce4-terminal dbus-x11 tigervnc-standalone-server tigervnc-tools xfonts-base fonts-dejavu-core adwaita-icon-theme
            apt-get clean
            rm -rf /var/lib/apt/lists/*
            rm -rf /root/.vnc
            rm -f /root/.lindroid-desktop
        """.trimIndent()

        DistroFlavor.MINT -> """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            apt-get purge -y mint-meta-cinnamon
            apt-get autoremove -y --purge
            apt-get clean
            rm -rf /var/lib/apt/lists/*
            rm -rf /root/.vnc /etc/apt/sources.list.d/mint.list
            rm -f /root/.lindroid-desktop
        """.trimIndent()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Linux removal", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Removes a container's desktop or the whole container"
            },
        )
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_terminal_notification)
        .setContentTitle("Lindroid")
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
        private const val CHANNEL_ID = "lindroid_uninstall"
        private const val NOTIFICATION_ID = 1204
    }
}
