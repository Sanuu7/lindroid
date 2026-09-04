package dev.lindroid.app

import android.app.Application
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
import dev.lindroid.app.shizuku.ShizukuState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LindroidViewModel(application: Application) : AndroidViewModel(application) {
    private val paths = RuntimePaths(application)
    private val mutableInstallState = MutableStateFlow<InstallState>(
        if (paths.marker.isFile) InstallState.Installed else InstallState.NotInstalled,
    )

    val installState = mutableInstallState.asStateFlow()
    val terminalOutput = SessionBus.output
    val sessionStatus = SessionBus.status
    val sessionError = SessionBus.error
    val shizukuMode = ShizukuState.mode
    val desktopSetupStatus = DesktopSetupBus.status
    val desktopSetupLog = DesktopSetupBus.log
    val desktopSetupError = DesktopSetupBus.error
    val desktopSessionStatus = DesktopSessionBus.status

    init {
        DesktopSetupBus.refresh(application)
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
}
