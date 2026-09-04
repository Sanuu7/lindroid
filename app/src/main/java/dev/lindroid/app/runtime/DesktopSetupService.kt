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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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

    fun refresh(context: Context, containerId: String?) {
        if (mutableStatus.value == DesktopSetupStatus.INSTALLING) return
        val installed = containerId != null &&
            RuntimePaths(context, containerId).rootfs.resolve(DESKTOP_MARKER).isFile
        mutableStatus.value = if (installed) DesktopSetupStatus.INSTALLED else DesktopSetupStatus.NOT_INSTALLED
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (process?.isAlive != true) installDesktop(intent?.getStringExtra(EXTRA_CONTAINER) ?: RuntimePaths.DEFAULT_ID)
        return START_NOT_STICKY
    }

    private fun installDesktop(containerId: String) {
        val container = ContainerRegistry.find(this, containerId) ?: return
        val flavor = container.flavor
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Installing the ${flavor.label} desktop…"))
        DesktopSetupBus.status(DesktopSetupStatus.INSTALLING)
        DesktopSetupBus.append("Preparing the ${flavor.desktopLabel} desktop.\n")

        scope.launch {
            try {
                ensureFreeSpace(flavor)
                val prebuilt = RuntimePaths(this@DesktopSetupService).prebuiltImage(flavor)
                if (prebuilt != null) {
                    try {
                        if (!prebuilt.isFile) downloadPrebuilt(prebuilt)
                        installFromPrebuilt(prebuilt, containerId, flavor)
                    } catch (error: Throwable) {
                        android.util.Log.e("DesktopSetupService", "Prebuilt install failed", error)
                        DesktopSetupBus.append(
                            "Prebuilt image unavailable (${error.message}); falling back to APT.\n",
                        )
                        installFromApt(containerId, flavor)
                    }
                } else {
                    installFromApt(containerId, flavor)
                }
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                android.util.Log.e("DesktopSetupService", "Desktop setup failed", error)
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
     * The image is published as release assets on the project repository and
     * can also be sideloaded into the prebuilt folder. A 522 MB image downloads
     * in minutes; unpacking it is plain host-side Java.
     */
    private suspend fun downloadPrebuilt(prebuilt: File) {
        DesktopSetupBus.append("Downloading the prebuilt desktop image…\n")
        val sidecar = File(prebuilt.parentFile, prebuilt.name + ".sha256")
        fetchToFile("$PREBUILT_BASE${prebuilt.name}.sha256", sidecar, null)
        fetchToFile("$PREBUILT_BASE${prebuilt.name}", File(prebuilt.parentFile, prebuilt.name + ".part")) { fraction ->
            DesktopSetupBus.append("Downloading… ${"%.0f".format(fraction * 100)}%\n")
        }
        val partial = File(prebuilt.parentFile, prebuilt.name + ".part")
        // Rename before verifying: the digest helper expects the sidecar named
        // exactly like the image plus .sha256.
        check(partial.renameTo(prebuilt)) { "Could not store the desktop image" }
        try {
            verifyPrebuiltDigest(prebuilt)
        } catch (error: Throwable) {
            prebuilt.delete()
            throw error
        }
    }

    private fun fetchToFile(url: String, target: File, onProgress: ((Float) -> Unit)?) {
        val request = Request.Builder().url(url).header("User-Agent", "Lindroid/0.1 (Android; arm64)").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(1 shl 19)
                    var copied = 0L
                    var lastPercent = -10
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (onProgress != null && total > 0) {
                            val percent = ((copied * 100) / total).toInt()
                            if (percent >= lastPercent + 5) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Fast path: the flavor ships a ready-made desktop image (built natively
     * on an x86_64 machine, where no emulation is involved). Unpacking it is
     * plain host-side Java and takes about the download time.
     */
    private suspend fun installFromPrebuilt(prebuilt: File, containerId: String, flavor: DistroFlavor) {
        val paths = RuntimePaths(this, containerId)
        verifyPrebuiltDigest(prebuilt)
        DesktopSetupBus.append("Unpacking the prebuilt ${flavor.label} desktop image.\n")
        paths.rootfs.deleteRecursively()
        check(paths.rootfs.mkdirs()) { "Could not prepare the container directory" }
        RootfsArchive.extract(prebuilt, paths.rootfs) { fraction ->
            DesktopSetupBus.append("Unpacking… ${"%.0f".format(fraction * 100)}%\n")
        }
        check(paths.marker.isFile) { "The image is missing its install marker" }
        check(paths.rootfs.resolve(DesktopSetupBus.DESKTOP_MARKER).isFile) {
            "The image is missing the desktop marker"
        }
        DesktopSetupBus.append("Desktop installation complete.\n")
        DesktopSetupBus.status(DesktopSetupStatus.INSTALLED)
        // The unpacked system is on disk; the 522 MB image is only needed again
        // after a reinstall, so free the space.
        prebuilt.delete()
        File(prebuilt.parentFile, prebuilt.name + ".sha256").delete()
    }

    /** The image must carry a matching `<name>.sha256` sidecar file. */
    private fun verifyPrebuiltDigest(file: File) {
        val sidecar = File(file.parentFile, file.name + ".sha256")
        check(sidecar.isFile) { "Missing ${sidecar.name} next to the desktop image" }
        val expected = sidecar.readText().trim().split(Regex("\\s+")).first().lowercase()
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        val actual = hash.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) { "The desktop image checksum does not match ${sidecar.name}" }
    }

    private suspend fun installFromApt(containerId: String, flavor: DistroFlavor) {
        DesktopSetupBus.append("No prebuilt image found; installing through APT. This is a one-time download.\n")
        val command = ProotRuntime.cleanEnvironment(
            listOf("/bin/bash", "-lc", setupScript(flavor)),
        )
        val running = ProotRuntime.processBuilder(this@DesktopSetupService, command, containerId).start()
        process = running
        running.inputStream.reader().useLines { lines ->
            lines.forEach { DesktopSetupBus.append("$it\n") }
        }
        val exit = running.waitFor()
        val installed = exit == 0 &&
            RuntimePaths(this@DesktopSetupService, containerId).rootfs.resolve(DesktopSetupBus.DESKTOP_MARKER).isFile
        if (installed) {
            DesktopSetupBus.append("Desktop installation complete.\n")
            DesktopSetupBus.status(DesktopSetupStatus.INSTALLED)
        } else {
            error("APT exited with code $exit")
        }
    }

    /**
     * The desktop download and unpack need real space on the app's filesystem.
     * Stats the app directory itself: it always exists, unlike a container's
     * rootfs that may not have been created yet.
     */
    private fun ensureFreeSpace(flavor: DistroFlavor) {
        val available = StatFs(applicationContext.filesDir.path).availableBytes
        check(available >= flavor.minimumDesktopBytes) {
            val needed = flavor.minimumDesktopBytes / (1024f * 1024f * 1024f)
            val free = available / (1024f * 1024f * 1024f)
            "Not enough free space: the desktop needs about %.1f GB and only %.1f GB is free".format(needed, free)
        }
    }

    private fun setupScript(flavor: DistroFlavor): String = when (flavor) {
        DistroFlavor.DEBIAN -> DEBIAN_SETUP_SCRIPT
        DistroFlavor.MINT -> MINT_SETUP_SCRIPT
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Desktop setup", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Downloads and configures desktop packages inside a container"
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
        private const val EXTRA_CONTAINER = "container"

        /** Release assets on the project repository: the tarball plus its .sha256 sidecar. */
        private const val PREBUILT_BASE = "https://github.com/Sanuu7/lindroid/releases/download/mint-xfce-v1/"

        private val DEBIAN_SETUP_SCRIPT = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y --no-install-recommends xfce4 xfce4-terminal dbus-x11 tigervnc-standalone-server tigervnc-tools xfonts-base fonts-dejavu-core adwaita-icon-theme
            apt-get clean
            rm -rf /var/lib/apt/lists/*
            mkdir -p /root/.config/xfce4 /root/Desktop
            touch /root/.lindroid-desktop
        """.trimIndent()

        private val MINT_SETUP_SCRIPT = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            if test -f /etc/apt/sources.list.d/ubuntu.sources; then
              sed -i 's/^Components:.*/Components: main universe restricted multiverse/' /etc/apt/sources.list.d/ubuntu.sources
            fi
            printf '%s\n' 'deb [trusted=yes] http://packages.linuxmint.com wilma main upstream import backport' > /etc/apt/sources.list.d/mint.list
            apt-get update
            apt-get install -y linuxmint-keyring || true
            apt-get update
            apt-get install -y --no-install-recommends mint-meta-xfce xfce4-terminal dbus-x11 tigervnc-standalone-server tigervnc-tools xfonts-base fonts-dejavu-core adwaita-icon-theme || true
            for round in 1 2 3 4 5 6; do
              out=${'$'}(dpkg --configure -a 2>&1) || true
              failed=${'$'}(printf '%s\n' "${'$'}out" | sed -n 's/^dpkg: error processing package \([^ ]*\).*/\1/p' | sort -u)
              if test -z "${'$'}failed"; then break; fi
              for p in ${'$'}failed; do
                f=/var/lib/dpkg/info/${'$'}p.postinst
                test -f "${'$'}f" && printf '#!/bin/sh\nexit 0\n' > "${'$'}f"
              done
            done
            dpkg --configure -a || true
            apt-get clean
            rm -rf /var/lib/apt/lists/*
            mkdir -p /root/.config /root/Desktop
            touch /root/.lindroid-desktop
        """.trimIndent()

        fun start(context: Context, containerId: String) {
            context.startForegroundService(
                Intent(context, DesktopSetupService::class.java).putExtra(EXTRA_CONTAINER, containerId),
            )
        }
    }
}
