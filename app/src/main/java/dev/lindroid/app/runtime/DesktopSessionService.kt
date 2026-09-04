package dev.lindroid.app.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.Display
import androidx.core.app.NotificationCompat
import dev.lindroid.app.R
import dev.lindroid.app.desktop.DesktopActivity
import dev.lindroid.app.desktop.RfbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import kotlin.math.roundToInt

enum class DesktopSessionStatus {
    STOPPED,
    STARTING,
    RUNNING,
    FAILED,
}

object DesktopSessionBus {
    private val mutableStatus = MutableStateFlow(DesktopSessionStatus.STOPPED)
    private val mutablePassword = MutableStateFlow<String?>(null)
    private val mutableMessage = MutableStateFlow("Desktop is stopped")

    val status = mutableStatus.asStateFlow()
    val password = mutablePassword.asStateFlow()
    val message = mutableMessage.asStateFlow()

    internal fun update(status: DesktopSessionStatus, message: String, password: String? = mutablePassword.value) {
        mutableStatus.value = status
        mutableMessage.value = message
        mutablePassword.value = password
    }
}

class DesktopSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopDesktop()
        } else if (process?.isAlive != true) {
            startDesktop(intent?.getStringExtra(EXTRA_CONTAINER) ?: RuntimePaths.DEFAULT_ID)
        }
        return START_NOT_STICKY
    }

    private fun startDesktop(containerId: String) {
        val container = ContainerRegistry.find(this, containerId)
        if (container == null || !RuntimePaths(this, containerId).rootfs.resolve(DesktopSetupBus.DESKTOP_MARKER).isFile) {
            DesktopSessionBus.update(DesktopSessionStatus.FAILED, "Install the desktop for this container first")
            stopSelf()
            return
        }
        val flavor = container.flavor

        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting the ${flavor.label} desktop…"))
        val password = randomPassword()
        DesktopSessionBus.update(DesktopSessionStatus.STARTING, "Starting ${flavor.desktopLabel} and its private display…", password)

        scope.launch {
            try {
                val (geometry, dpi) = displayProfile()
                val command = ProotRuntime.cleanEnvironment(
                    command = listOf("/bin/bash", "-lc", desktopScript(flavor)),
                    extra = mapOf(
                        "LINDROID_VNC_PASSWORD" to password,
                        "LINDROID_VNC_GEOMETRY" to geometry,
                        "LINDROID_VNC_DPI" to dpi.toString(),
                    ),
                )
                val running = ProotRuntime.processBuilder(this@DesktopSessionService, command, containerId).start()
                process = running
                val recentLines = java.util.Collections.synchronizedList(mutableListOf<String>())

                launch {
                    running.inputStream.reader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotBlank()) {
                                recentLines.add(line)
                                if (recentLines.size > 30) recentLines.removeAt(0)
                                DesktopSessionBus.update(
                                    DesktopSessionBus.status.value,
                                    line.take(180),
                                    password,
                                )
                            }
                        }
                    }
                }

                if (!waitForVnc(running)) {
                    running.destroy()
                    val hint = if (!running.isAlive && recentLines.isNotEmpty()) {
                        " Early output: ${recentLines.takeLast(5).joinToString(" | ").take(400)}"
                    } else {
                        " Check /root/.vnc/Xtigervnc.log and /root/.vnc/desktop.log in the terminal."
                    }
                    error("The ${flavor.desktopLabel} display did not start in time.$hint")
                }
                DesktopSessionBus.update(DesktopSessionStatus.RUNNING, "${flavor.label} ${flavor.desktopLabel} is running", password)
                updateNotification("${flavor.label} ${flavor.desktopLabel} is running")

                val exit = running.waitFor()
                if (DesktopSessionBus.status.value != DesktopSessionStatus.STOPPED) {
                    DesktopSessionBus.update(
                        if (exit == 0) DesktopSessionStatus.STOPPED else DesktopSessionStatus.FAILED,
                        if (exit == 0) "Desktop stopped" else "Desktop exited with code $exit",
                        null,
                    )
                }
            } catch (error: Throwable) {
                DesktopSessionBus.update(
                    DesktopSessionStatus.FAILED,
                    error.message ?: error.javaClass.simpleName,
                    null,
                )
            } finally {
                process = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Sizes the desktop to the phone's own panel so it fills the screen at a
     * one-to-one scale instead of letterboxing inside a fixed 1280x720 box.
     */
    private fun displayProfile(): Pair<String, Int> {
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val width = ((mode?.physicalWidth ?: 1280).coerceIn(640, 2560) / 2) * 2
        val height = ((mode?.physicalHeight ?: 720).coerceIn(640, 2560) / 2) * 2
        val dpi = ((resources.configuration.densityDpi * 0.65f).roundToInt()).coerceIn(96, 240)
        return "${width}x${height}" to dpi
    }

    private suspend fun waitForVnc(process: Process): Boolean {
        repeat(90) {
            if (!process.isAlive) return false
            val connected = runCatching {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress(InetAddress.getLoopbackAddress(), RfbClient.VNC_PORT),
                        250,
                    )
                }
                true
            }.getOrDefault(false)
            if (connected) return true
            delay(500)
        }
        return false
    }

    private fun stopDesktop() {
        DesktopSessionBus.update(DesktopSessionStatus.STOPPED, "Desktop stopped", null)
        process?.destroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun desktopScript(flavor: DistroFlavor): String = """
        set -e
        mkdir -p /root/.vnc /tmp/.X11-unix /tmp/lindroid-runtime
        chmod 700 /root/.vnc /tmp/lindroid-runtime
        rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
        PASSWD_BIN=${'$'}(command -v tigervncpasswd || command -v vncpasswd)
        XVNC_BIN=${'$'}(command -v Xtigervnc || command -v Xvnc)
        test -n "${'$'}PASSWD_BIN" -a -n "${'$'}XVNC_BIN"
        printf '%s\n' "${'$'}LINDROID_VNC_PASSWORD" | "${'$'}PASSWD_BIN" -f > /root/.vnc/passwd
        chmod 600 /root/.vnc/passwd
        test -s /etc/machine-id || dbus-uuidgen --ensure=/etc/machine-id
        "${'$'}XVNC_BIN" :1 -geometry "${'$'}LINDROID_VNC_GEOMETRY" -depth 24 -dpi "${'$'}LINDROID_VNC_DPI" -localhost -SecurityTypes VncAuth -PasswordFile /root/.vnc/passwd -AlwaysShared -ac > /root/.vnc/Xtigervnc.log 2>&1 &
        VNC_PID=${'$'}!
        export DISPLAY=:1
        export XDG_RUNTIME_DIR=/tmp/lindroid-runtime
        for attempt in ${'$'}(seq 1 60); do
            test -S /tmp/.X11-unix/X1 && break
            kill -0 "${'$'}VNC_PID" 2>/dev/null || { echo "Xtigervnc exited early; see /root/.vnc/Xtigervnc.log"; cat /root/.vnc/Xtigervnc.log; exit 1; }
            sleep 0.25
        done
        test -S /tmp/.X11-unix/X1 || { echo "Xtigervnc never created its X socket; see /root/.vnc/Xtigervnc.log"; cat /root/.vnc/Xtigervnc.log; exit 1; }
        ${flavor.desktopSessionCommand} > /root/.vnc/desktop.log 2>&1 &
        wait "${'$'}VNC_PID"
    """

    private fun randomPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return buildString { repeat(8) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Linux desktop", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the local graphical desktop running"
            },
        )
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_terminal_notification)
        .setContentTitle("Lindroid desktop")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, DesktopActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0,
            "Stop",
            PendingIntent.getService(
                this,
                1,
                Intent(this, DesktopSessionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        process?.destroy()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "lindroid_desktop"
        private const val NOTIFICATION_ID = 1203
        private const val ACTION_STOP = "dev.lindroid.app.STOP_DESKTOP"
        private const val EXTRA_CONTAINER = "container"

        fun start(context: Context, containerId: String) {
            context.startForegroundService(
                Intent(context, DesktopSessionService::class.java).putExtra(EXTRA_CONTAINER, containerId),
            )
        }

        /**
         * Reattach path: opens the desktop for whatever container is active,
         * used when the activity is restored without an explicit start.
         */
        fun startActive(context: Context) {
            start(context, ContainerRegistry.load(context).activeId)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DesktopSessionService::class.java).setAction(ACTION_STOP))
        }
    }
}
