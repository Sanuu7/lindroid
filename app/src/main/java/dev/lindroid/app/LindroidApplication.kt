package dev.lindroid.app

import android.app.Application
import dev.lindroid.app.shizuku.ShizukuState
import rikka.shizuku.Shizuku

class LindroidApplication : Application() {
    private val binderReceived = Shizuku.OnBinderReceivedListener { ShizukuState.refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { ShizukuState.refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> ShizukuState.refresh() }

    override fun onCreate() {
        super.onCreate()
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        ShizukuState.refresh()
    }
}
