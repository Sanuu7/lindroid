package dev.lindroid.app.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.lindroid.app.MainActivity
import dev.lindroid.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.OutputStreamWriter

enum class SessionStatus {
    STOPPED,
    STARTING,
    RUNNING,
    FAILED,
}

object SessionBus {
    private const val MAX_OUTPUT = 160_000
    private val lock = Any()
    private val buffer = StringBuilder()
    private val mutableOutput = MutableStateFlow("")
    private val mutableStatus = MutableStateFlow(SessionStatus.STOPPED)
    private val mutableError = MutableStateFlow<String?>(null)

    val output = mutableOutput.asStateFlow()
    val status = mutableStatus.asStateFlow()
    val error = mutableError.asStateFlow()

    internal fun setStatus(value: SessionStatus, errorMessage: String? = null) {
        mutableStatus.value = value
        mutableError.value = errorMessage
    }

    internal fun append(value: String) = synchronized(lock) {
        buffer.append(value)
        if (buffer.length > MAX_OUTPUT) buffer.delete(0, buffer.length - MAX_OUTPUT)
        mutableOutput.value = buffer.toString()
    }

    fun clear() = synchronized(lock) {
        buffer.clear()
        mutableOutput.value = ""
    }
}

class LinuxSessionService : Service() {
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private var containerId: String = RuntimePaths.DEFAULT_ID

    inner class LocalBinder : Binder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            else -> if (process?.isAlive != true) {
                containerId = intent?.getStringExtra(EXTRA_CONTAINER) ?: RuntimePaths.DEFAULT_ID
                startSession()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        val paths = RuntimePaths(this, containerId)
        if (!paths.marker.isFile) {
            SessionBus.setStatus(SessionStatus.FAILED, "This container is not installed")
            stopSelf()
            return
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Starting the Linux session…"))
        SessionBus.setStatus(SessionStatus.STARTING)
        SessionBus.append("\nStarting the Linux session on Android's kernel…\n")

        scope.launch {
            try {
                paths.prepareHostDirectories()
                val started = ProotRuntime.processBuilder(
                    this@LinuxSessionService,
                    ProotRuntime.cleanEnvironment(
                        listOf("/bin/bash", "--noprofile", "--rcfile", "/root/.bashrc", "-i"),
                    ),
                    containerId,
                ).start()

                process = started
                writer = BufferedWriter(OutputStreamWriter(started.outputStream))
                SessionBus.setStatus(SessionStatus.RUNNING)
                updateNotification("Debian terminal is running")

                readerJob = launch {
                    started.inputStream.reader().use { reader ->
                        val chunk = CharArray(2048)
                        while (true) {
                            val count = reader.read(chunk)
                            if (count < 0) break
                            SessionBus.append(String(chunk, 0, count))
                        }
                    }
                }

                val exit = started.waitFor()
                readerJob?.join()
                SessionBus.append("\n[Session exited with code $exit]\n")
                SessionBus.setStatus(SessionStatus.STOPPED)
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                SessionBus.append("\n[Could not start the Linux session: $message]\n")
                SessionBus.setStatus(SessionStatus.FAILED, message)
            } finally {
                writer = null
                process = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopSession() {
        runCatching {
            writer?.apply {
                write("exit\n")
                flush()
            }
        }
        process?.destroy()
        SessionBus.setStatus(SessionStatus.STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendCommand(command: String) {
        val activeWriter = writer ?: return
        scope.launch {
            runCatching {
                SessionBus.append("\n$ $command\n")
                activeWriter.write(command)
                activeWriter.newLine()
                activeWriter.flush()
            }.onFailure {
                SessionBus.setStatus(SessionStatus.FAILED, it.message)
            }
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.session_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.session_channel_description) },
        )
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_terminal_notification)
        .setContentTitle("Lindroid")
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0,
            "Stop",
            PendingIntent.getService(
                this,
                1,
                Intent(this, LinuxSessionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        process?.destroy()
        if (activeInstance === this) activeInstance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "lindroid_session"
        private const val NOTIFICATION_ID = 1201
        private const val ACTION_STOP = "dev.lindroid.app.STOP_SESSION"
        private const val EXTRA_CONTAINER = "container"

        fun start(context: Context, containerId: String = RuntimePaths.DEFAULT_ID) {
            context.startForegroundService(
                Intent(context, LinuxSessionService::class.java).putExtra(EXTRA_CONTAINER, containerId),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LinuxSessionService::class.java).setAction(ACTION_STOP))
        }

        fun send(command: String) {
            activeInstance?.sendCommand(command)
        }

        @Volatile
        private var activeInstance: LinuxSessionService? = null
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The foreground service intentionally keeps a user-started shell alive.
        super.onTaskRemoved(rootIntent)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        SessionBus.append("\n[Android reports low memory]\n")
    }
}
