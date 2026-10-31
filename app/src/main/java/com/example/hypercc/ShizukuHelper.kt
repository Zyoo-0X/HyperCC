package com.example.hypercc

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

object ShizukuHelper {
    private var shell: IShell? = null
    private var appCtx: Context? = null
    private var binding = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            shell = IShell.Stub.asInterface(binder)
            binding = false
            applyStock()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            shell = null
            binding = false
        }
    }

    fun binderAlive() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun granted() = binderAlive() &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun ready() = shell != null

    fun requestPermission() { if (binderAlive()) runCatching { Shizuku.requestPermission(100) } }

    fun connect(ctx: Context) {
        appCtx = ctx.applicationContext
        if (!granted()) return
        if (shell != null) { applyStock(); return }
        if (binding) return
        binding = true
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShellService::class.java.name))
            .processNameSuffix("shell").daemon(false).debuggable(false).version(1)
        runCatching { Shizuku.bindUserService(args, conn) }.onFailure { binding = false }
    }

    fun run(cmd: String) {
        val s = shell ?: return
        Thread { runCatching { s.exec(cmd) } }.start()
    }

    fun setQsDisabled(disabled: Boolean, cb: ((String) -> Unit)? = null) {
        val s = shell ?: run { cb?.invoke("Shizuku is not connected"); return }
        Thread {
            val r = runCatching { s.setQsDisabled(disabled) }.getOrElse { "ERR: ${it.message}" }
            cb?.invoke(r)
        }.start()
    }

    fun setShadeDisabled(disabled: Boolean, cb: ((String) -> Unit)? = null) {
        val s = shell ?: run { cb?.invoke("Shizuku is not connected"); return }
        Thread {
            val r = runCatching { s.setShadeDisabled(disabled) }.getOrElse { "ERR: ${it.message}" }
            cb?.invoke(r)
        }.start()
    }

    fun applyStock() {
        val ctx = appCtx ?: return
        val prefs = Prefs(ctx)
        if (!prefs.enabled) {
            // Suis utama OFF: pulihkan shade sistem sepenuhnya.
            setShadeDisabled(false)
            setQsDisabled(false)
            return
        }
        // Matikan shade sistem sepenuhnya (HyperCC ada panel notifikasi sendiri) atau sekurang-kurangnya QS sistem.
        setShadeDisabled(prefs.disableShade)
        setQsDisabled(prefs.disableShade || prefs.hideStockQs)
    }
}
