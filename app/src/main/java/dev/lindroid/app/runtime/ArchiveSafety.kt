package dev.lindroid.app.runtime

import java.io.IOException

internal object ArchiveSafety {
    fun safeRelativePath(raw: String): String? {
        val normalized = raw.removePrefix("./").removePrefix("/")
        if (normalized.isBlank()) return null
        val parts = normalized.split('/').filter { it.isNotBlank() && it != "." }
        if (parts.any { it == ".." }) throw IOException("Unsafe path in Debian image: $raw")
        return parts.joinToString("/")
    }
}
