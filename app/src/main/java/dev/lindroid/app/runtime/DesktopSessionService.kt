package dev.lindroid.app.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
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
            startDesktop()
        }
        return START_NOT_STICKY
    }

    private fun startDesktop() {
        val paths = RuntimePaths(this)
        if (!paths.rootfs.resolve(DesktopSetupBus.DESKTOP_MARKER).isFile) {
            DesktopSessionBus.update(DesktopSessionStatus.FAILED, "Install the XFCE desktop first")
            stopSelf()
            return
        }

        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting the Debian desktop…"))
        val password = randomPassword()
        DesktopSessionBus.update(DesktopSessionStatus.STARTING, "Starting XFCE and its private display…", password)

        scope.launch {
            try {
                val command = ProotRuntime.cleanEnvironment(
                    command = listOf("/bin/bash", "-lc", DESKTOP_SCRIPT),
                    extra = mapOf("LINDROID_VNC_PASSWORD" to password),
                )
                val running = ProotRuntime.processBuilder(this@DesktopSessionService, command).start()
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
                        " Check /root/.vnc/Xtigervnc.log and /root/.vnc/xfce.log in the Debian terminal."
                    }
                    error("The XFCE display did not start in time.$hint")
                }
                DesktopSessionBus.update(DesktopSessionStatus.RUNNING, "Debian XFCE is running", password)
                updateNotification("Debian XFCE is running")

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

    private fun randomPassword(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return buildString { repeat(8) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Linux desktop", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the local Debian graphical desktop running"
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
        private const val DESKTOP_SCRIPT = """
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
            "${'$'}XVNC_BIN" :1 -geometry 1280x720 -depth 24 -dpi 120 -localhost -SecurityTypes VncAuth -PasswordFile /root/.vnc/passwd -AlwaysShared -ac > /root/.vnc/Xtigervnc.log 2>&1 &
            VNC_PID=${'$'}!
            export DISPLAY=:1
            export XDG_RUNTIME_DIR=/tmp/lindroid-runtime
            for attempt in ${'$'}(seq 1 60); do
                test -S /tmp/.X11-unix/X1 && break
                kill -0 "${'$'}VNC_PID" 2>/dev/null || { echo "Xtigervnc exited early; see /root/.vnc/Xtigervnc.log"; cat /root/.vnc/Xtigervnc.log; exit 1; }
                sleep 0.25
            done
            test -S /tmp/.X11-unix/X1 || { echo "Xtigervnc never created its X socket; see /root/.vnc/Xtigervnc.log"; cat /root/.vnc/Xtigervnc.log; exit 1; }
            dbus-launch --exit-with-session startxfce4 > /root/.vnc/xfce.log 2>&1 &
            wait "${'$'}VNC_PID"
        """

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DesktopSessionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DesktopSessionService::class.java).setAction(ACTION_STOP))
        }
    }
}
