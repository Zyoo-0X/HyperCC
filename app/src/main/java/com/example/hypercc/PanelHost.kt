package com.example.hypercc

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.max

/**
 * Satu panel (shade) yang terapung di atas skrin: tetingkap overlay + blur latar + animasi/seret.
 * Kandungan: Quick Settings, Notifications, atau kedua-duanya (mod satu panel).
 */
class PanelHost(
    private val svc: ControlCenterService, private val wm: WindowManager, initialKind: Int,
    private val backdrop: Bitmap? = null,
    private val startEditing: Boolean = false
) {

    var kind: Int = initialKind
        private set

    private val prefs = Prefs(svc)
    private val pal = Palette.build(svc, prefs)
    private val handler = Handler(Looper.getMainLooper())

    private val screenH: Int = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.height()
    else svc.resources.displayMetrics.heightPixels
    private val statusH: Int
    private val navH: Int
    private val hyperFullScreen: Boolean = prefs.panelStyle == 1

    private var qs: QuickSettingsUi? = null
    private var notifs: NotificationsUi? = null
    private var scroller: ScrollView? = null
    private var contentSlot: LinearLayout? = null
    private var maxContentH = 0
    private var pageChanging = false

    // Blur dua cara: (1) tangkapan skrin yang di-blur GPU (paling boleh dipercayai), (2) blur-behind tetingkap sebagai sandaran.
    private val useBackdrop = Build.VERSION.SDK_INT >= 31 && backdrop != null && prefs.blur > 0
    private val windowBlur: Boolean = !useBackdrop &&
        Build.VERSION.SDK_INT >= 31 && prefs.blur > 0 && runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
    private val blurOk: Boolean = useBackdrop || windowBlur
    private var backdropView: ImageView? = null
    private val maxBlur = (prefs.blur / 100f * 150f).toInt()
    private val scrimMax = if (blurOk) (if (pal.dark) .30f else .16f) else (if (pal.dark) .60f else .40f)
    private val cardAlpha = (prefs.opacity / 100f).let { if (blurOk) it else max(it, .94f) }

    private val root = DragRoot(svc)
    private val card = FrameLayout(svc)
    private lateinit var lp: WindowManager.LayoutParams

    private var cardH = 0
    private var ready = false
    private var progress = 0f
    private var anim: ValueAnimator? = null
    private var pending: (() -> Unit)? = null
    private var destroyed = false
    private var closeBase = 0f

    /** true semasa pengguna sedang menyeret panel. */
    var dragging = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            val active = qs ?: return
            active.refresh()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        val res = svc.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        statusH = if (id > 0) res.getDimensionPixelSize(id) else 28.dp
        val navId = res.getIdentifier("navigation_bar_height", "dimen", "android")
        navH = if (navId > 0) res.getDimensionPixelSize(navId) else 24.dp

        val handleH = if (hyperFullScreen) 18.dp else 30.dp
        maxContentH = if (hyperFullScreen) screenH - statusH - navH - 22.dp
            else (screenH * 0.92f).toInt() - statusH - 10.dp - 6.dp - handleH
        val outer = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(if (hyperFullScreen) 10.dp else 16.dp,
                statusH + if (hyperFullScreen) 8.dp else 10.dp,
                if (hyperFullScreen) 10.dp else 16.dp,
                if (hyperFullScreen) navH else 6.dp)
        }
        val content = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        contentSlot = content
        outer.addView(content, if (hyperFullScreen) LinearLayout.LayoutParams(-1, 0, 1f)
            else LinearLayout.LayoutParams(-1, -2))
        renderPanelContent()

        // HyperOS fills the display; retain only a compact bottom gesture indicator.
        val handle = FrameLayout(svc)
        handle.addView(View(svc).apply {
            background = rounded(withAlpha(if (hyperFullScreen) android.graphics.Color.WHITE else pal.onVariant, .65f), 2.dpf)
        }, FrameLayout.LayoutParams(if (hyperFullScreen) 58.dp else 36.dp, 4.dp, Gravity.CENTER))
        outer.addView(handle, LinearLayout.LayoutParams(-1, handleH))

        card.background = if (hyperFullScreen) rounded(withAlpha(pal.surface, cardAlpha), 0f)
            else cornerShape(withAlpha(pal.surface, cardAlpha), 0f, 0f, 32.dpf, 32.dpf)
        card.addView(outer, FrameLayout.LayoutParams(-1, if (hyperFullScreen) -1 else -2))
        if (useBackdrop) {
            val r = (prefs.blur / 100f * 60f).coerceAtLeast(1f)
            backdropView = ImageView(svc).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(backdrop)
                setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
                alpha = 0f
            }
            root.addView(backdropView, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(card, FrameLayout.LayoutParams(-1, if (hyperFullScreen) -1 else -2, Gravity.TOP))
        card.visibility = View.INVISIBLE

        card.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0) {
                cardH = h
                if (!ready) { ready = true; card.visibility = View.VISIBLE }
                applyProgress(progress)
                pending?.let { pending = null; it() }
            }
        }
    }

    private fun renderPanelContent() {
        val slot = contentSlot ?: return
        qs?.dispose()
        notifs?.dispose()
        qs = null
        notifs = null
        scroller = null
        slot.removeAllViews()

        when (kind) {
            ControlCenterService.QS -> {
                val q = QuickSettingsUi(svc, pal, prefs, svc.tiles, startEditing)
                qs = q
                val sv = panelScroller(maxContentH)
                sv.addView(q.view, ViewGroup.LayoutParams(-1, -2))
                slot.addView(sv, LinearLayout.LayoutParams(-1, if (hyperFullScreen) -1 else -2))
                scroller = sv
            }
            ControlCenterService.NOTIF -> {
                val n = NotificationsUi(svc, pal)
                notifs = n
                slot.addView(n.header)
                val sv = panelScroller(maxContentH - 72.dp)
                sv.addView(n.listView, ViewGroup.LayoutParams(-1, -2))
                slot.addView(sv, LinearLayout.LayoutParams(-1, if (hyperFullScreen) 0 else -2).apply {
                    if (hyperFullScreen) weight = 1f
                })
                scroller = sv
            }
            else -> {
                val q = QuickSettingsUi(svc, pal, prefs, svc.tiles, startEditing)
                val n = NotificationsUi(svc, pal)
                qs = q
                notifs = n
                val inner = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
                inner.addView(q.view, LinearLayout.LayoutParams(-1, -2))
                inner.addView(n.header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20.dp })
                inner.addView(n.listView, LinearLayout.LayoutParams(-1, -2))
                val sv = panelScroller(maxContentH)
                sv.addView(inner, ViewGroup.LayoutParams(-1, -2))
                slot.addView(sv, LinearLayout.LayoutParams(-1, if (hyperFullScreen) -1 else -2))
                scroller = sv
            }
        }
    }


    private fun panelScroller(maxHeight: Int): ScrollView = if (hyperFullScreen) {
        ScrollView(svc).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isFillViewport = true
        }
    } else MaxHeightScrollView(svc, maxHeight)

    @Suppress("DEPRECATION")
    fun attach(): Boolean {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (windowBlur) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (runCatching { wm.addView(root, lp) }.isFailure) { destroyed = true; return false }
        qs?.refresh()
        notifs?.start()
        if (qs != null) handler.postDelayed(tick, 1000)
        return true
    }

    // ---------- Seret & animasi ----------

    /** Dipanggil semasa jari menyeret dari tepi atas. [dy] = jarak seret ke bawah dalam px. */
    fun drag(dy: Float) {
        dragging = true
        val span = if (cardH > 0) cardH * 0.45f else screenH * 0.3f
        applyProgress(dy / span)
    }

    fun release(vy: Float, cancelled: Boolean) {
        dragging = false
        val open = when {
            // Sistem (status bar) boleh "mencuri" sentuhan dan hantar CANCEL selepas gerak leret ke bawah sudah
            // bermula. Itu tetap niat membuka panel, jadi buka (bukan tutup).
            cancelled -> true
            vy > 400.dp -> true
            vy < -600.dp -> false
            else -> progress > 0.25f
        }
        if (open) open() else close(true)
    }

    fun open() = whenReady { animateTo(1f, null) }

    /** Tunjuk panel terus (tanpa animasi), dipakai bila panel dibuka semula selepas tukar tema/warna. */
    fun showNow() = whenReady { applyProgress(1f) }

    /** Leret mendatar menukar halaman QS/Notifications tanpa menutup shade. */
    private fun switchPage(targetKind: Int, direction: Int) {
        if (destroyed || pageChanging || kind == ControlCenterService.BOTH || targetKind == kind) return
        pageChanging = true
        dragging = true
        val width = root.width.takeIf { it > 0 } ?: svc.resources.displayMetrics.widthPixels
        card.animate().cancel()
        card.animate()
            .translationX(direction * width.toFloat())
            .setDuration(155)
            .withEndAction {
                kind = targetKind
                renderPanelContent()
                qs?.refresh()
                notifs?.start()
                handler.removeCallbacks(tick)
                if (qs != null) handler.postDelayed(tick, 1000)
                card.translationX = -direction * width.toFloat()
                card.requestLayout()
                card.post {
                    card.animate()
                        .translationX(0f)
                        .setDuration(205)
                        .withEndAction {
                            pageChanging = false
                            dragging = false
                        }
                        .start()
                }
            }
            .start()
    }

    fun close(animate: Boolean = true) {
        if (destroyed) return
        if (!animate || !ready) { destroy(); return }
        animateTo(0f) { destroy() }
    }

    private fun whenReady(block: () -> Unit) { if (ready) block() else pending = block }

    private fun animateTo(target: Float, end: (() -> Unit)?) {
        anim?.cancel()
        val from = progress
        if (from == target) { end?.invoke(); return }
        anim = ValueAnimator.ofFloat(from, target).apply {
            duration = (320 * abs(target - from)).toLong().coerceIn(140L, 320L)
            interpolator = if (target >= 1f) DecelerateInterpolator(1.7f) else AccelerateDecelerateInterpolator()
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled) end?.invoke() }
            })
            start()
        }
    }

    private fun applyProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        if (!ready) return
        card.translationY = -(1f - progress) * cardH
        root.setBackgroundColor(Color.argb((progress * scrimMax * 255f).toInt(), 0, 0, 0))
        backdropView?.alpha = progress
        if (windowBlur) {
            val r = ((progress * maxBlur).toInt() / 4) * 4
            if (r != lp.blurBehindRadius) {
                lp.blurBehindRadius = r
                runCatching { wm.updateViewLayout(root, lp) }
            }
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        anim?.cancel()
        handler.removeCallbacksAndMessages(null)
        qs?.dispose()
        notifs?.dispose()
        runCatching { wm.removeViewImmediate(root) }
        backdropView?.setImageDrawable(null)
        backdrop?.let { runCatching { it.recycle() } }
        svc.onPanelClosed(this)
    }

    /** Akar tetingkap: tutup menegak, dan tukar halaman dengan leretan mendatar. */
    private inner class DragRoot(ctx: Context) : FrameLayout(ctx) {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var closing = false
        private var horizontal = false
        private var downOnNotificationCard = false
        private var tracker: VelocityTracker? = null

        private fun canStartClose(e: MotionEvent): Boolean {
            val up = downY - e.rawY
            val dx = abs(downX - e.rawX)
            if (qs?.isEditing == true || qs?.isShowingDetails == true) return false
            val activeScroll = qs?.activeScroller ?: scroller
            // Jangan tutup semasa senarai masih boleh ditatal ke bawah; tutup apabila di hujung kandungan.
            return up > slop && up > dx && !(activeScroll?.canScrollVertically(1) ?: false)
        }

        private fun isDownOnSwipeCard(rawX: Float, rawY: Float): Boolean {
            fun contains(v: View): Boolean {
                if (v is SwipeCard) {
                    val location = IntArray(2)
                    v.getLocationOnScreen(location)
                    return rawX >= location[0] && rawX <= location[0] + v.width &&
                        rawY >= location[1] && rawY <= location[1] + v.height
                }
                if (v is ViewGroup) {
                    for (i in v.childCount - 1 downTo 0) {
                        if (contains(v.getChildAt(i))) return true
                    }
                }
                return false
            }
            return contains(card)
        }

        private fun canStartHorizontal(e: MotionEvent): Boolean {
            if (kind == ControlCenterService.BOTH || qs?.isEditing == true || qs?.isShowingDetails == true) return false
            val dx = e.rawX - downX
            val dy = e.rawY - downY
            // Dalam halaman notifikasi, leret ke kanan pada kad kembali ke Quick Settings.
            // Leret ke kiri pada kad kekal sebagai gesture buang notifikasi.
            if (downOnNotificationCard && !(kind == ControlCenterService.NOTIF && dx > 0f)) return false
            return abs(dx) > slop * 1.25f && abs(dx) > abs(dy) * 1.35f
        }

        private fun beginHorizontal(e: MotionEvent) {
            horizontal = true
            dragging = true
            card.animate().cancel()
            tracker?.recycle()
            tracker = VelocityTracker.obtain().also { it.addMovement(e) }
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        private fun startClose(e: MotionEvent) {
            closing = true
            dragging = true
            anim?.cancel()
            closeBase = e.rawY
            tracker?.recycle()
            tracker = VelocityTracker.obtain().also { it.addMovement(e) }
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        private fun finishClose(up: Boolean) {
            tracker?.computeCurrentVelocity(1000)
            val vy = tracker?.yVelocity ?: 0f
            tracker?.recycle(); tracker = null
            closing = false
            dragging = false
            val shouldClose = when {
                !up -> progress < 0.5f
                vy < -600.dp -> true
                vy > 600.dp -> false
                else -> progress < 0.65f
            }
            if (shouldClose) close(true) else open()
        }

        private fun finishHorizontal(up: Boolean) {
            tracker?.computeCurrentVelocity(1000)
            val vx = tracker?.xVelocity ?: 0f
            tracker?.recycle(); tracker = null
            horizontal = false
            dragging = false
            val dx = (lastTouchX - downX)
            val threshold = max(72.dp.toFloat(), root.width * 0.18f)
            val accepted = up && (abs(dx) >= threshold || (abs(vx) >= 650.dp && abs(dx) > slop))
            val direction = if (dx < 0) -1 else 1
            val target = when {
                accepted && kind == ControlCenterService.QS && direction < 0 -> ControlCenterService.NOTIF
                accepted && kind == ControlCenterService.NOTIF && direction > 0 -> ControlCenterService.QS
                else -> null
            }
            if (target != null) switchPage(target, direction)
            else card.animate().translationX(0f).setDuration(170).start()
        }

        private var lastTouchX = 0f

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    lastTouchX = e.rawX
                    closing = false
                    horizontal = false
                    downOnNotificationCard = isDownOnSwipeCard(e.rawX, e.rawY)
                }
                MotionEvent.ACTION_MOVE -> {
                    lastTouchX = e.rawX
                    if (!closing && !horizontal && canStartHorizontal(e)) {
                        beginHorizontal(e)
                        return true
                    }
                    if (!closing && !horizontal && canStartClose(e)) {
                        startClose(e)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> downOnNotificationCard = false
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    lastTouchX = e.rawX
                    closing = false
                    horizontal = false
                }
                MotionEvent.ACTION_MOVE -> {
                    lastTouchX = e.rawX
                    if (!closing && !horizontal && canStartHorizontal(e)) beginHorizontal(e)
                    if (!closing && !horizontal && canStartClose(e)) startClose(e)
                    if (horizontal) {
                        tracker?.addMovement(e)
                        val dx = e.rawX - downX
                        card.translationX = (dx * 0.72f).coerceIn(-root.width * 0.72f, root.width * 0.72f)
                    } else if (closing) {
                        tracker?.addMovement(e)
                        applyProgress(1f - (closeBase - e.rawY) / max(cardH, 1))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    lastTouchX = e.rawX
                    tracker?.addMovement(e)
                    if (horizontal) finishHorizontal(true)
                    else if (closing) finishClose(true)
                    else if (e.y > card.bottom + card.translationY && abs(e.rawY - downY) < slop * 2) close(true)
                    downOnNotificationCard = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    lastTouchX = e.rawX
                    tracker?.addMovement(e)
                    if (horizontal) finishHorizontal(false)
                    else if (closing) finishClose(false)
                    downOnNotificationCard = false
                }
            }
            return true
        }
    }
}
