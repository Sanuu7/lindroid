package dev.lindroid.app.runtime

import android.content.Context
import java.io.File

class RuntimePaths(context: Context, containerId: String = DEFAULT_ID) {
    val rootfs = File(context.filesDir, "distros/$containerId")
    val installStage = File(context.filesDir, "distros/$containerId.installing")
    val downloads = File(context.cacheDir, "downloads")
    val prootTemp = File(context.cacheDir, "proot")
    val shared = File(context.filesDir, "shared")
    val marker = File(rootfs, ".lindroid-installed")

    fun prepareHostDirectories() {
        downloads.mkdirs()
        prootTemp.mkdirs()
        shared.mkdirs()
    }

    companion object {
        /**
         * The historical container id. Existing installs keep their files
         * under distros/debian-12, so the default container reuses that path.
         */
        const val DEFAULT_ID = "debian-12"
    }
}
