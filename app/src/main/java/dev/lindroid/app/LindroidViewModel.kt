package dev.lindroid.app

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lindroid.app.runtime.DebianInstaller
import dev.lindroid.app.runtime.DesktopSessionBus
import dev.lindroid.app.runtime.DesktopSessionService
import dev.lindroid.app.runtime.DesktopSetupBus
import dev.lindroid.app.runtime.DesktopSetupService
import dev.lindroid.app.runtime.InstallState
import dev.lindroid.app.runtime.LinuxSessionService
import dev.lindroid.app.runtime.RuntimePaths
import dev.lindroid.app.runtime.SessionBus
import dev.lindroid.app.runtime.UninstallBus
import dev.lindroid.app.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SharedEntry(val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long)

class LindroidViewModel(application: Application) : AndroidViewModel(application) {
    private val paths = RuntimePaths(application)
    private val mutableInstallState = MutableStateFlow<InstallState>(
        if (paths.marker.isFile) InstallState.Installed else InstallState.NotInstalled,
    )
    private val mutableSharedEntries = MutableStateFlow<List<SharedEntry>>(emptyList())
    private val mutableSharedPath = MutableStateFlow("")
    private val mutableSharedNotice = MutableStateFlow<String?>(null)

    val installState = mutableInstallState.asStateFlow()
    val terminalOutput = SessionBus.output
    val sessionStatus = SessionBus.status
    val sessionError = SessionBus.error
    val shizukuMode = ShizukuState.mode
    val desktopSetupStatus = DesktopSetupBus.status
    val desktopSetupLog = DesktopSetupBus.log
    val desktopSetupError = DesktopSetupBus.error
    val desktopSessionStatus = DesktopSessionBus.status
    val sharedEntries = mutableSharedEntries.asStateFlow()
    val sharedPath = mutableSharedPath.asStateFlow()
    val sharedNotice = mutableSharedNotice.asStateFlow()

    init {
        DesktopSetupBus.refresh(application)
        viewModelScope.launch {
            UninstallBus.completed.collect {
                mutableInstallState.value = if (paths.marker.isFile) InstallState.Installed else InstallState.NotInstalled
                DesktopSetupBus.refresh(application)
            }
        }
        refreshSharedFiles()
    }

    fun installDebian() {
        if (mutableInstallState.value is InstallState.Installing) return
        viewModelScope.launch {
            mutableInstallState.value = InstallState.Installing(null, "Preparing installation…")
            runCatching {
                withContext(Dispatchers.IO) {
                    DebianInstaller(getApplication()).install { fraction, message ->
                        mutableInstallState.value = InstallState.Installing(fraction, message)
                    }
                }
            }.onSuccess {
                mutableInstallState.value = InstallState.Installed
            }.onFailure {
                mutableInstallState.value = InstallState.Failed(it.message ?: "Installation failed")
            }
        }
    }

    fun retryInstall() = installDebian()

    fun startSession() = LinuxSessionService.start(getApplication())

    fun stopSession() = LinuxSessionService.stop(getApplication())

    fun sendCommand(command: String) {
        if (command.isNotBlank()) LinuxSessionService.send(command.trimEnd())
    }

    fun clearTerminal() = SessionBus.clear()

    fun requestShizuku() = ShizukuState.requestPermission()

    fun installDesktop() = DesktopSetupService.start(getApplication())

    fun startDesktop() = DesktopSessionService.start(getApplication())

    fun stopDesktop() = DesktopSessionService.stop(getApplication())

    fun refreshSharedFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = sharedDirectory()
            mutableSharedEntries.value = dir.listFiles()
                ?.map { SharedEntry(it.name, it.isDirectory, it.length(), it.lastModified()) }
                ?.sortedWith(compareByDescending<SharedEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
                ?: emptyList()
        }
    }

    fun openSharedDirectory(entry: SharedEntry) {
        if (!entry.isDirectory) return
        mutableSharedPath.value = (mutableSharedPath.value.split('/').filter { it.isNotBlank() } + entry.name)
            .joinToString("/")
        refreshSharedFiles()
    }

    fun upSharedDirectory() {
        mutableSharedPath.value = mutableSharedPath.value.split('/').filter { it.isNotBlank() }
            .dropLast(1).joinToString("/")
        refreshSharedFiles()
    }

    fun importSharedFiles(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val target = sharedDirectory()
            var imported = 0
            var failed = 0
            uris.forEach { uri ->
                runCatching {
                    val name = queryDisplayName(resolver, uri) ?: "imported-${System.currentTimeMillis()}"
                    val safe = name.substringAfterLast('/').replace('/', '_').ifBlank { "imported" }
                    resolver.openInputStream(uri)?.use { input ->
                        File(target, uniqueName(target, safe)).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("empty stream")
                    imported++
                }.onFailure { failed++ }
            }
            mutableSharedNotice.value = when {
                imported == 0 && failed == 0 -> "Nothing was imported"
                failed == 0 -> "Imported $imported file${if (imported == 1) "" else "s"} into $sharedLocationLabel"
                else -> "Imported $imported, failed $failed"
            }
            refreshSharedFiles()
        }
    }

    fun exportSharedFile(entry: SharedEntry, target: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                File(sharedDirectory(), entry.name).inputStream().use { input ->
                    resolver.openOutputStream(target)?.use { output ->
                        input.copyTo(output)
                    } ?: throw IllegalStateException("empty stream")
                }
                mutableSharedNotice.value = "Saved ${entry.name} to your phone"
            }.onFailure {
                mutableSharedNotice.value = "Could not save ${entry.name}: ${it.message}"
            }
        }
    }

    fun deleteSharedEntry(entry: SharedEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(sharedDirectory(), entry.name)
            val removed = if (entry.isDirectory) file.deleteRecursively() else file.delete()
            mutableSharedNotice.value = if (removed) "Deleted ${entry.name}" else "Could not delete ${entry.name}"
            refreshSharedFiles()
        }
    }

    fun clearSharedNotice() {
        mutableSharedNotice.value = null
    }

    private val sharedLocationLabel: String
        get() = "/root/storage" + mutableSharedPath.value.split('/')
            .filter { it.isNotBlank() }.joinToString("/", "/")

    private fun sharedDirectory(): File {
        val relative = mutableSharedPath.value
        paths.shared.mkdirs()
        val dir = if (relative.isBlank()) paths.shared else paths.shared.resolve(relative)
        return if (dir.isDirectory && dir.absolutePath.startsWith(paths.shared.absolutePath)) dir
        else paths.shared.also { mutableSharedPath.value = "" }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun uniqueName(dir: File, name: String): String {
        var candidate = name
        var index = 1
        while (File(dir, candidate).exists()) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) "${name.substring(0, dot)}-$index${name.substring(dot)}" else "$name-$index"
            index++
        }
        return candidate
    }
}
