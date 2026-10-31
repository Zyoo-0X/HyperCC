package com.example.hypercc

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import rikka.shizuku.Shizuku
import kotlin.math.abs
import kotlin.math.max

/**
 * Menyediakan "pemicu" nipis di tepi atas skrin. Leret ke bawah dari:
 *  - sebelah kanan  -> Quick Settings
 *  - sebelah kiri   -> Notifications
 * (boleh ditukar / digabungkan dalam tetapan). Panel mengikut jari, seperti shade Android sebenar.
 */
class ControlCenterService : AccessibilityService() {

    companion object {
        const val QS = 0
        const val NOTIF = 1
        const val BOTH = 2
        @Volatile var instance: ControlCenterService? = null
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    lateinit var tiles: Tiles
        private set

    private val main = Handler(Looper.getMainLooper())
    private var trigger: View? = null
    private var host: PanelHost? = null
    private var shownAt = 0L
    private var shot: Bitmap? = null
    private var shotAt = 0L
    private var shotReq = 0L
    private var lastTakeOver = 0L
    private var lastDownKind = QS
    private var lastDownAt = 0L

    private val binderListener = Shizuku.OnBinderReceivedListener { ShizukuHelper.connect(this) }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        main.post {
            when (key) {
                "enabled" -> applyEnabled()
                "trigger_level" -> if (prefs.enabled) installTrigger()
                "disable_stock_qs", "disable_stock_shade", "disable_system_shade" -> ShizukuHelper.applyStock()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        tiles = Tiles(this).also { it.start() }
        runCatching { Shizuku.addBinderReceivedListenerSticky(binderListener) }
        ShizukuHelper.connect(this)
        prefs.listen(prefListener)
        HyperNotificationListener.ensureBound(this)
        applyEnabled()
    }

    /** Hidup/matikan seluruh HyperCC. OFF = kembali normal, tiada panel dan tiada notifikasi custom. */
    private fun applyEnabled() {
        if (prefs.enabled) {
            runCatching { tiles.stop() }
            tiles.start()
            installTrigger()
            ShizukuHelper.applyStock()
        } else {
            main.removeCallbacksAndMessages(null)
            closePanel(false)
            removeTrigger()
            shot?.let { runCatching { it.recycle() } }
            shot = null
            tiles.stop()
            // Pulihkan shade sistem + Quick Settings sistem.
            ShizukuHelper.setShadeDisabled(false)
            ShizukuHelper.setQsDisabled(false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!::prefs.isInitialized || !prefs.enabled) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.android.systemui") { handleSystemUi(event); return }
        val h = host ?: return
        if (h.dragging || SystemClock.uptimeMillis() - shownAt < 700) return
        // Tutup bila pengguna beralih ke app lain (Home, buka app...). Abaikan diri sendiri.
        if (pkg == packageName) return
        closePanel(true)
    }

    private fun isLocked() = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /** Jika shade sistem terbuka (tarikan terkena SystemUI), tutup dan gantikan dengan panel HyperCC. */
    private fun handleSystemUi(e: AccessibilityEvent) {
        if (!::prefs.isInitialized || !prefs.takeover || Build.VERSION.SDK_INT < 31) return
        if ((e.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED) != 0) return
        // Kenal pasti shade melalui teks DAN nama class, supaya tidak bergantung pada bahasa telefon / ROM.
        val t = e.text.joinToString(" ").lowercase()
        val c = e.className?.toString()?.lowercase() ?: ""
        val qsShade = t.contains("quick settings") || c.contains("quicksettings") || c.contains("qspanel")
        val notifShade = t.contains("notification shade") || c.contains("notificationshade") ||
            c.contains("notificationpanel") || c.contains("shadewindow")
        val recentTouch = SystemClock.uptimeMillis() - lastDownAt < 1500
        // Tiada petunjuk teks/class, tetapi pengguna baru sahaja menyentuh pemicu: anggap itu tarikan shade sistem.
        if (!qsShade && !notifShade && !recentTouch) return
        if (!qsShade && !notifShade && c.isNotEmpty() &&
            !(c.contains("shade") || c.contains("statusbar") || c.contains("panel"))) return
        if (isLocked()) return
        if (host != null) { dismissStockShade(); return }
        val now = SystemClock.uptimeMillis()
        if (now - lastTakeOver < 1500) return
        lastTakeOver = now
        dismissStockShade()
        // Ikut sisi yang pengguna sentuh (hormati tetapan "tukar sisi"), bukan sisi yang sistem pilih.
        val kind = when {
            prefs.singlePanel -> BOTH
            recentTouch -> lastDownKind
            qsShade -> QS
            else -> NOTIF
        }
        // Guna tangkapan skrin dari saat sentuhan (sebelum shade sistem muncul), bukan tangkapan baru.
        main.postDelayed({ if (host == null) beginPanel(kind)?.open() }, 200)
    }

    private fun dismissStockShade() {
        if (Build.VERSION.SDK_INT >= 31) runCatching { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) }
    }

    // ---------- Latar blur (tangkap skrin, kemudian blur dengan GPU) ----------

    private fun captureBackdrop(done: (() -> Unit)? = null) {
        val now = SystemClock.uptimeMillis()
        if (Build.VERSION.SDK_INT < 31 || prefs.blur <= 0 || now - shotReq < 700) { done?.invoke(); return }
        shotReq = now
        val ok = runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val buf = r.hardwareBuffer
                    val bmp = runCatching { Bitmap.wrapHardwareBuffer(buf, r.colorSpace) }.getOrNull()
                    buf.close()
                    shot?.let { runCatching { it.recycle() } }
                    shot = bmp
                    shotAt = SystemClock.uptimeMillis()
                    done?.invoke()
                }
                override fun onFailure(errorCode: Int) { done?.invoke() }
            })
        }.isSuccess
        if (!ok) done?.invoke()
    }

    private fun takeBackdrop(): Bitmap? {
        val b = shot
        shot = null
        if (b != null && SystemClock.uptimeMillis() - shotAt < 5000) return b
        b?.let { runCatching { it.recycle() } }
        return null
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        closePanel(false)
        if (::wm.isInitialized && prefs.enabled) installTrigger()
    }

    override fun onDestroy() {
        instance = null
        runCatching { Shizuku.removeBinderReceivedListener(binderListener) }
        if (::prefs.isInitialized) prefs.unlisten(prefListener)
        if (::tiles.isInitialized) tiles.stop()
        closePanel(false)
        removeTrigger()
        // Pulihkan shade sistem supaya telefon tak tinggal tanpa akses notifikasi.
        ShizukuHelper.setShadeDisabled(false)
        ShizukuHelper.setQsDisabled(false)
        super.onDestroy()
    }

    // ---------- Pemicu di tepi atas ----------

    private fun triggerHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val status = if (id > 0) resources.getDimensionPixelSize(id) else 28.dp
        return when (prefs.triggerSize) {
            0 -> status                // Kecil: setinggi status bar sahaja
            2 -> max(status, 72.dp)    // Besar
            3 -> max(status, 96.dp)    // Sangat besar
            else -> max(status, 48.dp) // Biasa (lalai)
        }
    }

    private fun installTrigger() {
        removeTrigger()
        if (!prefs.enabled) return
        val v = View(this)
        v.setOnTouchListener(TriggerTouch())
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, triggerHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (runCatching { wm.addView(v, lp) }.isSuccess) trigger = v
    }

    private fun removeTrigger() {
        trigger?.let { runCatching { wm.removeViewImmediate(it) } }
        trigger = null
    }

    private fun kindFor(left: Boolean): Int {
        if (prefs.singlePanel) return BOTH
        val leftIsNotifications = !prefs.swapSides
        return if (left == leftIsNotifications) NOTIF else QS
    }

    private inner class TriggerTouch : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@ControlCenterService).scaledTouchSlop
        private var startX = 0f
        private var startY = 0f
        private var kind = QS
        private var session: PanelHost? = null
        private var tracker: VelocityTracker? = null

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    session = null
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                    kind = kindFor(e.rawX < v.width / 2f)
                    lastDownKind = kind
                    lastDownAt = SystemClock.uptimeMillis()
                    captureBackdrop()
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dy = e.rawY - startY
                    val dx = e.rawX - startX
                    if (session == null && dy > slop && dy > abs(dx)) {
                        session = beginPanel(kind)
                        if (session != null && prefs.haptics) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    session?.drag(dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(e)
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    session?.release(vy, e.actionMasked == MotionEvent.ACTION_CANCEL)
                    session = null
                    tracker?.recycle(); tracker = null
                }
            }
            return true
        }
    }

    // ---------- Panel ----------

    private fun beginPanel(kind: Int, startEditing: Boolean = false): PanelHost? {
        if (!prefs.enabled || isLocked()) return null
        closePanel(false)
        val h = PanelHost(this, wm, kind, takeBackdrop(), startEditing)
        if (!h.attach()) return null
        host = h
        shownAt = SystemClock.uptimeMillis()
        if (prefs.takeover && Build.VERSION.SDK_INT >= 31) {
            // Jika shade sistem sempat terbuka dalam gerak yang sama, tutup semula.
            listOf(0L, 150L, 350L, 800L, 1300L).forEach { d -> main.postDelayed({ if (host === h) dismissStockShade() }, d) }
        }
        return h
    }

    /** Dipanggil dari skrin tetapan untuk cuba panel serta-merta. */
    fun showPanel(kind: Int) {
        if (!prefs.enabled) return
        captureBackdrop { beginPanel(kind)?.open() }
    }

    /** Bina semula panel yang sedang terbuka dalam mod edit (selepas tema/warna ditukar) supaya warna baru terpakai. */
    fun reopenEditing() {
        val kind = host?.kind ?: return
        main.post { beginPanel(kind, true)?.showNow() }
    }

    fun closePanel(animate: Boolean = true) {
        val h = host ?: return
        host = null
        h.close(animate)
    }

    fun onPanelClosed(h: PanelHost) {
        if (host === h) host = null
    }

    // ---------- Tindakan ----------

    fun launch(intent: Intent) {
        closePanel(false)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    fun openSettings(action: String, uri: String? = null) {
        val i = Intent(action)
        if (!uri.isNullOrBlank()) i.data = Uri.parse(uri)
        launch(i)
    }

    fun openApp() = launch(Intent(this, MainActivity::class.java))

    /** Hantar PendingIntent notifikasi. Android 14+ perlu kebenaran eksplisit untuk melancarkan activity. */
    fun sendPending(pi: PendingIntent?) {
        if (pi == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                val o = ActivityOptions.makeBasic()
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(this, 0, null, null, null, null, o.toBundle())
            } else {
                pi.send()
            }
        }
    }
}
