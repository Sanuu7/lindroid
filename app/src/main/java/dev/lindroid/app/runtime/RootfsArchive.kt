package dev.lindroid.app.runtime

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.FileOutputStream

/**
 * Unpacks a gzipped tar archive into a rootfs directory with the same safety
 * rules the OCI installer uses: path traversal is rejected, whiteouts are
 * applied, and symlinks and hard links are reproduced.
 */
internal object RootfsArchive {
    fun extract(archive: File, destination: File, onProgress: ((Float) -> Unit)? = null) {
        val totalBytes = archive.length().coerceAtLeast(1)
        var lastPercent = -1
        val counted = object : FilterInputStream(BufferedInputStream(FileInputStream(archive))) {
            var read = 0L
                private set

            override fun read(): Int {
                val value = super.read()
                if (value >= 0) read++
                return value
            }

            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                val count = super.read(buffer, off, len)
                if (count > 0) read += count
                return count
            }
        }
        fun report() {
            if (onProgress == null) return
            val percent = ((counted.read * 100) / totalBytes).toInt().coerceIn(0, 100)
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent / 100f)
            }
        }

        val pendingHardLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(counted)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val relative = ArchiveSafety.safeRelativePath(entry.name) ?: continue
                val output = File(destination, relative)

                if (entry.name.substringAfterLast('/') == ".wh..wh..opq") {
                    output.parentFile?.listFiles()?.forEach { it.deleteRecursively() }
                    continue
                }
                if (entry.name.substringAfterLast('/').startsWith(".wh.")) {
                    File(output.parentFile, entry.name.substringAfterLast('/').removePrefix(".wh.")).deleteRecursively()
                    continue
                }

                output.parentFile?.mkdirs()
                when {
                    entry.isDirectory -> output.mkdirs()
                    entry.isSymbolicLink -> {
                        output.deleteRecursively()
                        Os.symlink(entry.linkName, output.absolutePath)
                    }
                    entry.isLink -> {
                        val target = File(destination, ArchiveSafety.safeRelativePath(entry.linkName) ?: continue)
                        pendingHardLinks += output to target
                    }
                    entry.isFile -> {
                        FileOutputStream(output).use { tar.copyTo(it) }
                        runCatching { Os.chmod(output.absolutePath, entry.mode) }
                    }
                }
                report()
            }
        }
        pendingHardLinks.forEach { (link, target) ->
            link.deleteRecursively()
            runCatching { Os.link(target.absolutePath, link.absolutePath) }
                .getOrElse { target.copyTo(link, overwrite = true) }
        }
    }
}
