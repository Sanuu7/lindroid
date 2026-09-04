package dev.lindroid.app.runtime

import android.content.Context
import android.system.Os
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class DebianInstaller(context: Context) {
    private val paths = RuntimePaths(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    fun install(report: (Float?, String) -> Unit) {
        paths.prepareHostDirectories()
        paths.installStage.deleteRecursively()
        check(paths.installStage.mkdirs()) { "Could not create the installation directory" }

        try {
            report(null, "Contacting the Debian registry…")
            val token = registryToken()
            val manifest = resolveArm64Manifest(token)
            val layers = manifest.getJSONArray("layers")

            for (index in 0 until layers.length()) {
                val layer = layers.getJSONObject(index)
                val digest = layer.getString("digest")
                val expectedSize = layer.optLong("size", -1L)
                val layerNumber = index + 1
                report(index.toFloat() / layers.length(), "Downloading Debian layer $layerNumber of ${layers.length()}")
                val archive = downloadLayer(token, digest, expectedSize) { bytes, total ->
                    val withinLayer = if (total > 0) bytes.toFloat() / total else 0f
                    report(
                        (index + withinLayer) / layers.length(),
                        "Downloading Debian • ${formatBytes(bytes)}",
                    )
                }
                report((index + 0.92f) / layers.length(), "Unpacking Debian layer $layerNumber")
                extractLayer(archive, paths.installStage)
                archive.delete()
            }

            configureRootfs(paths.installStage)
            paths.rootfs.parentFile?.mkdirs()
            paths.rootfs.deleteRecursively()
            check(paths.installStage.renameTo(paths.rootfs)) { "Could not activate the Debian installation" }
            paths.marker.writeText("debian:12-slim\n")
            report(1f, "Debian is ready")
        } catch (error: Throwable) {
            paths.installStage.deleteRecursively()
            throw error
        }
    }

    private fun registryToken(): String {
        val url = "https://auth.docker.io/token".toHttpUrl().newBuilder()
            .addQueryParameter("service", "registry.docker.io")
            .addQueryParameter("scope", "repository:library/debian:pull")
            .build()
        val json = getJson(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
        return json.optString("token").ifBlank { json.getString("access_token") }
    }

    private fun resolveArm64Manifest(token: String): JSONObject {
        val initial = registryRequest("manifests/12-slim", token, MANIFEST_ACCEPT)
        val body = getJson(initial)
        if (!body.has("manifests")) return body

        val manifests = body.getJSONArray("manifests")
        var digest: String? = null
        for (index in 0 until manifests.length()) {
            val candidate = manifests.getJSONObject(index)
            val platform = candidate.optJSONObject("platform") ?: continue
            if (platform.optString("os") == "linux" && platform.optString("architecture") == "arm64") {
                digest = candidate.getString("digest")
                break
            }
        }
        checkNotNull(digest) { "The Debian image does not contain an ARM64 build" }
        return getJson(registryRequest("manifests/$digest", token, MANIFEST_ACCEPT))
    }

    private fun downloadLayer(
        token: String,
        digest: String,
        expectedSize: Long,
        progress: (Long, Long) -> Unit,
    ): File {
        val output = File(paths.downloads, digest.substringAfter(':') + ".tar.gz")
        val request = registryRequest("blobs/$digest", token, "application/octet-stream")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Registry returned HTTP ${response.code}")
            val body = response.body ?: throw IOException("The registry returned an empty layer")
            val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
            val hash = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                FileOutputStream(output).use { file ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        file.write(buffer, 0, count)
                        hash.update(buffer, 0, count)
                        copied += count
                        progress(copied, total)
                    }
                }
            }
            val actual = hash.digest().joinToString("") { "%02x".format(it) }
            check(digest == "sha256:$actual") { "Debian layer checksum verification failed" }
        }
        return output
    }

    private fun extractLayer(archive: File, destination: File) {
        val pendingHardLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive))),
        ).use { tar ->
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
            }
        }
        pendingHardLinks.forEach { (link, target) ->
            link.deleteRecursively()
            runCatching { Os.link(target.absolutePath, link.absolutePath) }
                .getOrElse { target.copyTo(link, overwrite = true) }
        }
    }

    private fun configureRootfs(root: File) {
        File(root, "root").mkdirs()
        File(root, "tmp").apply {
            mkdirs()
            runCatching { Os.chmod(absolutePath, 0b111111111) }
        }
        File(root, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            deleteRecursively()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "root/.bashrc").writeText(
            """
            export PS1='\[\e[1;92m\]lindroid\[\e[0m\]:\[\e[1;94m\]\w\[\e[0m\]$ '
            export TERM=xterm-256color
            alias ll='ls -alF'
            """.trimIndent() + "\n",
        )
    }

    private fun registryRequest(path: String, token: String, accept: String) = Request.Builder()
        .url("https://registry-1.docker.io/v2/library/debian/$path")
        .header("Authorization", "Bearer $token")
        .header("Accept", accept)
        .header("User-Agent", USER_AGENT)
        .build()

    private fun getJson(request: Request): JSONObject = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("Registry returned HTTP ${response.code}")
        JSONObject(response.body?.string() ?: throw IOException("The registry returned an empty response"))
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }

    private companion object {
        const val USER_AGENT = "Lindroid/0.1 (Android; arm64)"
        const val MANIFEST_ACCEPT =
            "application/vnd.oci.image.index.v1+json, " +
                "application/vnd.docker.distribution.manifest.list.v2+json, " +
                "application/vnd.oci.image.manifest.v1+json, " +
                "application/vnd.docker.distribution.manifest.v2+json"
    }
}
