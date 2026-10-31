package com.example.hypercc

import android.os.Binder
import android.os.IBinder
import kotlin.system.exitProcess

/** Berjalan sebagai uid shell (2000) melalui Shizuku. */
class ShellService : IShell.Stub() {

    private val token = Binder() // jika proses ini mati, flag disable dibatalkan automatik

    override fun destroy() {
        runCatching { setQsDisabled(false) }
        runCatching { setShadeDisabled(false) }
        exitProcess(0)
    }

    override fun exec(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        out + err
    } catch (e: Throwable) {
        "ERR: ${e.message}"
    }

    private fun statusBarService(): Any {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "statusbar") as IBinder
        val stub = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }

    /** Panggil IStatusBarService.disable / disable2 (dengan fallback ke versi ...ForUser). */
    private fun callDisable(name: String, flags: Int): String = try {
        val svc = statusBarService()
        try {
            svc.javaClass.getMethod(name, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
                .invoke(svc, flags, token, "com.android.shell")
        } catch (e: NoSuchMethodException) {
            svc.javaClass.getMethod(name + "ForUser", Int::class.javaPrimitiveType, IBinder::class.java, String::class.java, Int::class.javaPrimitiveType)
                .invoke(svc, flags, token, "com.android.shell", 0)
        }
        "OK"
    } catch (e: Throwable) {
        "ERR: ${e.cause?.message ?: e.message}"
    }

    /** DISABLE2_QUICK_SETTINGS = 1 */
    override fun setQsDisabled(disabled: Boolean): String = callDisable("disable2", if (disabled) 1 else 0)

    /** DISABLE_EXPAND = 0x00010000: shade sistem (notifikasi + QS) tak boleh ditarik langsung. */
    override fun setShadeDisabled(disabled: Boolean): String = callDisable("disable", if (disabled) 0x00010000 else 0)
}
