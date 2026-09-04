package dev.lindroid.app.runtime

sealed interface InstallState {
    data object NotInstalled : InstallState
    data class Installing(val fraction: Float?, val message: String) : InstallState
    data object Installed : InstallState
    data class Failed(val message: String) : InstallState
}
