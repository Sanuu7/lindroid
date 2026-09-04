package dev.lindroid.app.runtime

import android.content.Context
import java.io.File

class RuntimePaths(context: Context) {
    val rootfs = File(context.filesDir, "distros/debian-12")
    val installStage = File(context.filesDir, "distros/debian-12.installing")
    val downloads = File(context.cacheDir, "downloads")
    val prootTemp = File(context.cacheDir, "proot")
    val shared = File(context.filesDir, "shared")
    val marker = File(rootfs, ".lindroid-installed")

    fun prepareHostDirectories() {
        downloads.mkdirs()
        prootTemp.mkdirs()
        shared.mkdirs()
    }
}
