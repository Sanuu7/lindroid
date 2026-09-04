package dev.lindroid.app.runtime

import android.content.Context
import java.io.File

internal object ProotRuntime {
    fun processBuilder(context: Context, guestCommand: List<String>): ProcessBuilder {
        val paths = RuntimePaths(context)
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val proot = File(nativeDir, "libproot.so")
        val loader = File(nativeDir, "libproot_loader.so")
        check(proot.isFile && loader.isFile) { "The ARM64 PRoot runtime is missing" }
        check(paths.marker.isFile) { "Debian is not installed" }
        paths.prepareHostDirectories()

        val command = buildList {
            add(proot.absolutePath)
            add("--link2symlink")
            add("-0")
            add("-r")
            add(paths.rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            add("-b")
            add("${paths.shared.absolutePath}:/root/storage")
            add("-w")
            add("/root")
            addAll(guestCommand)
        }

        return ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = paths.prootTemp.absolutePath
                environment()["PROOT_NO_SECCOMP"] = "1"
                environment()["LD_LIBRARY_PATH"] = nativeDir
            }
    }

    fun cleanEnvironment(command: List<String>, extra: Map<String, String> = emptyMap()): List<String> = buildList {
        add("/usr/bin/env")
        add("-i")
        add("HOME=/root")
        add("USER=root")
        add("LOGNAME=root")
        add("SHELL=/bin/bash")
        add("TERM=xterm-256color")
        add("LANG=C.UTF-8")
        add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        extra.forEach { (key, value) -> add("$key=$value") }
        addAll(command)
    }
}
