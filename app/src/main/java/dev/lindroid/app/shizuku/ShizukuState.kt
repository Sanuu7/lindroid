package dev.lindroid.app.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuMode {
    UNAVAILABLE,
    WAITING_FOR_PERMISSION,
    ADB,
    ROOT,
}

object ShizukuState {
    private const val REQUEST_CODE = 4102
    private val mutableMode = MutableStateFlow(ShizukuMode.UNAVAILABLE)
    val mode = mutableMode.asStateFlow()

    fun refresh() {
        mutableMode.value = try {
            if (!Shizuku.pingBinder()) {
                ShizukuMode.UNAVAILABLE
            } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                ShizukuMode.WAITING_FOR_PERMISSION
            } else if (Shizuku.getUid() == 0) {
                ShizukuMode.ROOT
            } else {
                ShizukuMode.ADB
            }
        } catch (_: Throwable) {
            ShizukuMode.UNAVAILABLE
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (_: Throwable) {
            refresh()
        }
    }
}
