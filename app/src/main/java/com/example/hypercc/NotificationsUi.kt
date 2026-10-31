package com.example.hypercc

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs

/** Kad notifikasi yang boleh di-swipe ke tepi untuk dibuang. */
class SwipeCard(ctx: Context, private val canDismiss: Boolean, private val onDismiss: () -> Unit) : LinearLayout(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private var tracker: VelocityTracker? = null

    private fun isHorizontalDrag(e: MotionEvent): Boolean {
        val dx = e.rawX - downX
        val dy = e.rawY - downY
        return abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.5f
    }

    private fun begin(e: MotionEvent) {
        swiping = true
        isPressed = false
        parent?.requestDisallowInterceptTouchEvent(true)
        tracker?.recycle()
        tracker = VelocityTracker.obtain()
        downX = e.rawX
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; swiping = false }
            MotionEvent.ACTION_MOVE -> if (!swiping && isHorizontalDrag(e)) { begin(e); return true }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!swiping) {
            if (e.actionMasked == MotionEvent.ACTION_MOVE && isHorizontalDrag(e)) begin(e)
            else return super.onTouchEvent(e)
        }
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                var dx = e.rawX - downX
                if (!canDismiss) dx *= 0.2f
                translationX = dx
                alpha = 1f - (abs(dx) / (width * 1.2f)).coerceIn(0f, 0.7f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish(e.actionMasked == MotionEvent.ACTION_UP)
        }
        return true
    }

    private fun finish(up: Boolean) {
        tracker?.computeCurrentVelocity(1000)
        val vx = tracker?.xVelocity ?: 0f
        tracker?.recycle(); tracker = null
        swiping = false
        val dx = translationX
        val flung = abs(vx) > 900.dp && vx * dx > 0
        if (up && canDismiss && (abs(dx) > width * 0.38f || flung)) {
            val dir = if (dx >= 0) 1 else -1
            animate().translationX(dir * width.toFloat()).alpha(0f).setDuration(160).withEndAction {
                visibility = View.GONE
                onDismiss()
            }.start()
        } else {
            animate().translationX(0f).alpha(1f).setDuration(200).start()
        }
    }
}

/**
 * Senarai notifikasi sebenar. Kad dikumpul dengan sudut bulat besar di hujung dan kecil di tengah (gaya Android 16),
 * boleh di-tap untuk buka, swipe untuk buang, expand untuk teks penuh, dan dikemas kini secara langsung.
 */
class NotificationsUi(private val svc: ControlCenterService, private val pal: Palette) {

    val listView = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
    val header: View
    private val subtitle: TextView
    private val clearBtn: TextView

    private val onChanged: () -> Unit = { refresh() }
    private val pm = svc.packageManager
    private val labels = HashMap<String, String>()
    private val pkgCtx = HashMap<String, Context>()
    private val expanded = HashSet<String>()

    init {
        val titles = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(svc.text("Notifications", 26f, pal.onSurface, medium = true))
        subtitle = svc.text("", 13f, pal.onVariant).apply { setPadding(0, 4.dp, 0, 0) }
        titles.addView(subtitle)

        clearBtn = svc.text("Clear all", 14f, pal.onPrimaryContainer, medium = true).apply {
            setPadding(18.dp, 11.dp, 18.dp, 11.dp)
            background = rippleBg(pal.primaryContainer, 22.dpf, withAlpha(pal.onPrimaryContainer, .14f))
            setOnClickListener { HyperNotificationListener.dismissAll() }
        }

        header = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8.dp, 4.dp, 4.dp, 14.dp)
            addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
            addView(clearBtn)
        }
    }

    fun start() {
        HyperNotificationListener.addListener(onChanged)
        HyperNotificationListener.ensureBound(svc)
        refresh()
    }

    fun dispose() { HyperNotificationListener.removeListener(onChanged) }

    // ---------- Data ----------

    private fun isSummary(s: StatusBarNotification) = (s.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
    private fun isOngoing(s: StatusBarNotification) =
        (s.notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
    private fun clearable(s: StatusBarNotification) =
        (s.notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR)) == 0

    private fun isMedia(s: StatusBarNotification): Boolean {
        val n = s.notification
        val tpl = n.extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        return tpl.endsWith("MediaStyle") && (n.actions?.size ?: 0) > 0
    }

    private fun rank(s: StatusBarNotification) = when {
        isMedia(s) -> 0
        !isOngoing(s) -> 1
        else -> 2
    }

    private fun visibleItems(): List<StatusBarNotification> {
        val all = HyperNotificationListener.snapshot()
        val withChildren = all.filter { !isSummary(it) }.map { it.packageName to it.notification.group }.toSet()
        return all
            .filter { !(isSummary(it) && (it.packageName to it.notification.group) in withChildren) }
            .sortedWith(compareBy<StatusBarNotification>({ rank(it) }, { -it.postTime }))
    }

    // ---------- Render ----------

    fun refresh() {
        listView.removeAllViews()
        if (!HyperNotificationListener.isEnabled(svc)) {
            listView.addView(permissionCard())
            setHeader(0, false, "Notification access is off")
            return
        }
        val items = visibleItems()
        if (items.isEmpty()) {
            listView.addView(emptyView())
            setHeader(0, false, "You're all caught up")
            return
        }
        items.forEachIndexed { i, s ->
            listView.addView(buildCard(s, i, items.size), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 3.dp })
        }
        setHeader(items.size, items.any { clearable(it) }, if (items.size == 1) "1 notification" else "${items.size} notifications")
    }

    private fun setHeader(count: Int, canClear: Boolean, sub: String) {
        subtitle.text = sub
        clearBtn.isEnabled = canClear
        clearBtn.alpha = if (canClear) 1f else 0.38f
        clearBtn.visibility = if (count > 0) View.VISIBLE else View.GONE
    }

    private fun permissionCard(): View {
        val col = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 20.dp)
            background = rounded(pal.container, 26.dpf)
        }
        col.addView(svc.text("Turn on notification access", 16f, pal.onSurface, medium = true))
        col.addView(svc.text("Allow HyperCC to read your notifications so they appear here.", 14f, pal.onVariant).apply { setPadding(0, 6.dp, 0, 14.dp) })
        col.addView(svc.text("Open settings", 14f, pal.onPrimary, medium = true).apply {
            setPadding(20.dp, 11.dp, 20.dp, 11.dp)
            background = rippleBg(pal.primary, 22.dpf, withAlpha(pal.onPrimary, .16f))
            setOnClickListener { svc.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        }, LinearLayout.LayoutParams(-2, -2))
        return col
    }

    private fun emptyView(): View {
        val col = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 44.dp, 0, 44.dp)
        }
        col.addView(ImageView(svc).apply { setImageDrawable(svc.tinted(R.drawable.ic_notifications, withAlpha(pal.onVariant, .6f))) },
            LinearLayout.LayoutParams(44.dp, 44.dp))
        col.addView(svc.text("No notifications", 16f, pal.onVariant).apply { setPadding(0, 12.dp, 0, 0) })
        return col
    }

    // ---------- Helpers ----------

    private fun appLabel(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    private fun ctxFor(pkg: String): Context = pkgCtx.getOrPut(pkg) {
        runCatching { svc.createPackageContext(pkg, 0) }.getOrDefault(svc)
    }

    private fun cs(ex: Bundle, key: String): String? = ex.getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun timeAgo(whenMs: Long): String {
        val d = System.currentTimeMillis() - whenMs
        return when {
            d < 60_000 -> "now"
            d < 3_600_000 -> "${d / 60_000}m"
            d < 86_400_000 -> "${d / 3_600_000}h"
            else -> "${d / 86_400_000}d"
        }
    }

    private fun readable(c: Int): Int {
        val lum = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
        return when {
            pal.dark && lum < 0.55f -> Palette.mix(c, Color.WHITE, 0.55f)
            !pal.dark && lum > 0.65f -> Palette.mix(c, Color.BLACK, 0.45f)
            else -> c
        }
    }

    @Suppress("DEPRECATION")
    private fun expandedText(ex: Bundle): String? {
        val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }?.filter { it.isNotBlank() }
        if (!lines.isNullOrEmpty()) return lines.joinToString("\n")
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            val list = runCatching { Notification.MessagingStyle.Message.getMessagesFromBundleArray(msgs) }.getOrNull()
            if (!list.isNullOrEmpty()) {
                return list.takeLast(5).joinToString("\n") { m ->
                    val who = if (Build.VERSION.SDK_INT >= 28) m.senderPerson?.name?.toString() else m.sender?.toString()
                    if (who.isNullOrBlank()) "${m.text}" else "$who: ${m.text}"
                }
            }
        }
        return null
    }

    private fun finishAction(pi: android.app.PendingIntent?) {
        svc.sendPending(pi)
        svc.closePanel(true)
    }

    private fun buildCard(s: StatusBarNotification, index: Int, total: Int): View {
        val n = s.notification
        val ex = n.extras
        val key = s.key
        val pkg = s.packageName
        val media = isMedia(s)
        val dismissable = clearable(s)

        val bigR = 26.dpf
        val smallR = 6.dpf
        val top = if (index == 0) bigR else smallR
        val bottom = if (index == total - 1) bigR else smallR

        val card = SwipeCard(svc, dismissable) { HyperNotificationListener.dismiss(key) }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 12.dp, 16.dp, 14.dp)
            background = rippleBg(pal.container, top, top, bottom, bottom, withAlpha(pal.onSurface, .10f))
            isClickable = true
        }

        // --- Baris atas: ikon app, nama app, masa, anak panah expand ---
        val head = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tint = readable(if (n.color != 0) n.color else pal.primary)
        val smallDrawable: Drawable? = runCatching { n.getSmallIcon()?.loadDrawable(ctxFor(pkg)) }.getOrNull()
        val iconView = ImageView(svc).apply {
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            if (smallDrawable != null) {
                setImageDrawable(smallDrawable)
                setColorFilter(tint)
                background = rounded(withAlpha(tint, .16f), 12.dpf)
            } else {
                setImageDrawable(runCatching { pm.getApplicationIcon(pkg) }.getOrNull())
            }
        }
        head.addView(iconView, LinearLayout.LayoutParams(24.dp, 24.dp))
        val whenMs = if (n.`when` > 0) n.`when` else s.postTime
        head.addView(svc.text("${appLabel(pkg)} · ${timeAgo(whenMs)}", 12f, pal.onVariant, medium = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(8.dp, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(head)

        val title = cs(ex, Notification.EXTRA_TITLE_BIG) ?: cs(ex, Notification.EXTRA_TITLE) ?: ""
        val text = cs(ex, Notification.EXTRA_TEXT)
        val big = cs(ex, Notification.EXTRA_BIG_TEXT)
        val collapsed = text ?: big ?: ""
        val full = expandedText(ex) ?: big ?: collapsed
        val hasMore = !media && full.isNotEmpty() && full != collapsed

        if (media) buildMedia(card, s, title, collapsed) else {
            val titleView = svc.text(title, 15f, pal.onSurface, medium = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 8.dp, 0, 0)
            }
            if (title.isNotEmpty()) card.addView(titleView)
            val body = svc.text(collapsed, 14f, pal.onVariant).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 2.dp, 0, 0)
                setLineSpacing(0f, 1.08f)
            }
            if (collapsed.isNotEmpty()) card.addView(body)

            if (hasMore) {
                val chevron = ImageView(svc).apply {
                    setPadding(4.dp, 4.dp, 4.dp, 4.dp)
                    setImageDrawable(svc.tinted(R.drawable.ic_expand, pal.onVariant))
                }
                fun apply(expand: Boolean) {
                    body.text = if (expand) full else collapsed
                    body.maxLines = if (expand) 12 else 2
                    chevron.rotation = if (expand) 180f else 0f
                }
                if (key in expanded) apply(true)
                chevron.setOnClickListener {
                    val e = key !in expanded
                    if (e) expanded.add(key) else expanded.remove(key)
                    apply(e)
                }
                head.addView(chevron, LinearLayout.LayoutParams(32.dp, 32.dp))
            }

            val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
            if (max > 0 || indeterminate) {
                val pb = ProgressBar(svc, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = indeterminate
                    if (!indeterminate) { this.max = max; progress = ex.getInt(Notification.EXTRA_PROGRESS, 0) }
                    progressTintList = ColorStateList.valueOf(pal.primary)
                    progressBackgroundTintList = ColorStateList.valueOf(withAlpha(pal.primary, .22f))
                    indeterminateTintList = ColorStateList.valueOf(pal.primary)
                }
                card.addView(pb, LinearLayout.LayoutParams(-1, 8.dp).apply { topMargin = 10.dp })
            }

            val actions = (n.actions ?: emptyArray()).filter {
                it.actionIntent != null && !it.title.isNullOrBlank() && (it.remoteInputs?.isEmpty() != false)
            }.take(3)
            if (actions.isNotEmpty()) {
                val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 10.dp, 0, 0) }
                actions.forEach { a ->
                    row.addView(svc.text(a.title, 13f, pal.primary, medium = true).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        setPadding(14.dp, 8.dp, 14.dp, 8.dp)
                        background = rippleBg(pal.high, 18.dpf, withAlpha(pal.primary, .16f))
                        setOnClickListener { finishAction(a.actionIntent) }
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
                }
                card.addView(row)
            }
        }

        // --- Tap / tekan lama ---
        card.setOnClickListener {
            val pi = n.contentIntent
            if (pi != null) {
                svc.sendPending(pi)
                if ((n.flags and Notification.FLAG_AUTO_CANCEL) != 0) HyperNotificationListener.dismiss(key)
                svc.closePanel(true)
            }
        }
        card.setOnLongClickListener {
            svc.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
            true
        }
        return card
    }

    private fun buildMedia(card: LinearLayout, s: StatusBarNotification, title: String, subtitleText: String) {
        val n = s.notification
        val pkgContext = ctxFor(s.packageName)
        val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 10.dp, 0, 0) }
        val art = ImageView(svc).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(pal.high, 14.dpf)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setImageDrawable(runCatching { n.getLargeIcon()?.loadDrawable(pkgContext) }.getOrNull())
        }
        row.addView(art, LinearLayout.LayoutParams(56.dp, 56.dp))
        val texts = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; setPadding(14.dp, 0, 0, 0) }
        texts.addView(svc.text(title, 16f, pal.onSurface, medium = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        texts.addView(svc.text(subtitleText, 14f, pal.onVariant).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, 2.dp, 0, 0) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)

        val acts = n.actions ?: return
        val compact = n.extras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS)
        val idx = (compact?.toList() ?: acts.indices.toList()).filter { it in acts.indices }.take(5)
        val controls = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 6.dp, 0, 0) }
        idx.forEach { i ->
            val a = acts[i]
            val d = runCatching { a.getIcon()?.loadDrawable(pkgContext) }.getOrNull() ?: return@forEach
            d.mutate().setTint(pal.onSurface)
            controls.addView(ImageView(svc).apply {
                setPadding(12.dp, 12.dp, 12.dp, 12.dp)
                setImageDrawable(d)
                background = rippleBg(Color.TRANSPARENT, 24.dpf, withAlpha(pal.onSurface, .14f))
                setOnClickListener { svc.sendPending(a.actionIntent) }
            }, LinearLayout.LayoutParams(48.dp, 48.dp).apply { marginStart = 6.dp; marginEnd = 6.dp })
        }
        if (controls.childCount > 0) card.addView(controls)
    }
}
