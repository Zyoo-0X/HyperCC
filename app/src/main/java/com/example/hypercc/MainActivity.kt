package com.example.hypercc

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

/** Skrin tetapan gaya Android 16: kumpulan kad bulat, warna dinamik, pratonton langsung. */
class MainActivity : Activity() {

    companion object { const val EXTRA_REQUEST_WIFI_PERMISSIONS = "com.example.hypercc.REQUEST_WIFI_PERMISSIONS" }

    private lateinit var prefs: Prefs
    private lateinit var pal: Palette
    private lateinit var scroll: ScrollView

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ ->
        runOnUiThread { ShizukuHelper.connect(this); render() }
    }

    private val accents = listOf(
        "Blue" to 0xFF4285F4.toInt(), "Teal" to 0xFF00A6A6.toInt(), "Green" to 0xFF2E9E5B.toInt(),
        "Yellow" to 0xFFD9A400.toInt(), "Orange" to 0xFFF08C00.toInt(), "Red" to 0xFFE5484D.toInt(),
        "Pink" to 0xFFE0489A.toInt(), "Purple" to 0xFF8E5CF7.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        runCatching { Shizuku.addRequestPermissionResultListener(permListener) }
        scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        setContentView(scroll)
        render()
        requestWifiPermissionsFromIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestWifiPermissionsFromIntent()
    }

    private fun requestWifiPermissionsFromIntent() {
        if (!intent.getBooleanExtra(EXTRA_REQUEST_WIFI_PERMISSIONS, false)) return
        intent.removeExtra(EXTRA_REQUEST_WIFI_PERMISSIONS)
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            required += android.Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                required += android.Manifest.permission.ACCESS_COARSE_LOCATION
            }
            required += android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (required.isNotEmpty()) requestPermissions(required.toTypedArray(), 731)
    }

    override fun onResume() {
        super.onResume()
        ShizukuHelper.connect(this)
        render()
        scroll.postDelayed({ if (!isDestroyed) render() }, 800)
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permListener) }
        super.onDestroy()
    }

    // ================= Halaman =================

    private fun render() {
        val y = scroll.scrollY
        pal = Palette.build(this, prefs)
        window.statusBarColor = pal.surface
        window.navigationBarColor = pal.surface
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (pal.dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        scroll.setBackgroundColor(pal.surface)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 36.dp, 16.dp, 40.dp)
        }
        page.addView(text("HyperCC", 36f, pal.onSurface, light = true).apply { setPadding(8.dp, 0, 0, 0) })
        page.addView(text("Android 16 style shade for your phone", 15f, pal.onVariant).apply { setPadding(8.dp, 6.dp, 0, 18.dp) })

        page.addView(group(
            switchRow("Enable HyperCC", "Off = back to normal: system shade returns, no custom panel and no custom notifications",
                prefs.enabled) { prefs.enabled = it; render() }
        ))

        page.addView(preview(), lp(top = 16))
        page.addView(tryRow(), lp(top = 12))

        page.addView(label("Setup"))
        page.addView(setupGroup())

        page.addView(label("Appearance"))
        val appearanceRows = mutableListOf<View>(
            segmentRow("Control Center style", listOf("AOSP", "HyperOS"), prefs.panelStyle) { prefs.panelStyle = it; render() },
            note(if (prefs.panelStyle == 0)
                "AOSP style: wallpaper-based Android colors, a large clock, horizontal sliders and Material tiles."
            else
                "HyperOS full-screen Control Center: large Wi-Fi/mobile-data cards, equal-height media and vertical sliders, custom tile detail sheets, and four-column circular shortcuts."),
            segmentRow("Theme", listOf("Auto", "Light", "Dark"), prefs.theme) { prefs.theme = it; render() },
            colorRow()
        )
        if (prefs.panelStyle == 0) {
            appearanceRows += segmentRow("Tile shape", listOf("Dynamic", "Pill", "Rounded"), prefs.tileShape) { prefs.tileShape = it; render() }
            appearanceRows += segmentRow("Tile size", listOf("Large", "Small"), if (prefs.tileCols == 4) 1 else 0) { prefs.tileCols = if (it == 1) 4 else 2; render() }
        }
        appearanceRows += sliderRow("Background blur", prefs.blur / 100f, { "${(it * 100).toInt()}%" }) { prefs.blur = (it * 100).toInt() }
        appearanceRows += sliderRow("Panel opacity", (prefs.opacity - 50) / 50f, { "${(50 + it * 50).toInt()}%" }) { prefs.opacity = (50 + it * 50).toInt() }
        page.addView(group(*appearanceRows.toTypedArray()))
        if (Build.VERSION.SDK_INT < 31) {
            page.addView(note("Blur and wallpaper colors need Android 12 or newer. Older versions use a darker scrim and the Blue accent."))
        }

        page.addView(label("Gestures"))
        page.addView(group(
            segmentRow("Panels", listOf("Two panels", "One panel"), if (prefs.singlePanel) 1 else 0) { prefs.singlePanel = it == 1; render() },
            switchRow("Swap sides", "Default: left edge = Notifications, right edge = Quick Settings", prefs.swapSides) { prefs.swapSides = it },
            note("HyperOS mode: swipe left on Quick Settings to open Notifications; swipe right anywhere, including a notification card, to return. Swipe left on a notification card to dismiss it."),
            segmentRow("Swipe area height", listOf("Small", "Normal", "Large", "XL"), prefs.triggerSize) { prefs.triggerSize = it; render() },
            switchRow("Replace system shade", "If the system shade opens anyway, close it so only HyperCC shows (Android 12+)", prefs.takeover) { prefs.takeover = it },
            switchRow("Haptic feedback", "Vibrate lightly when opening the panel and tapping tiles", prefs.haptics) { prefs.haptics = it }
        ))

        page.addView(label("Quick Settings tiles"))
        page.addView(tileEditor())

        page.addView(label("System"))
        page.addView(group(
            switchRow("Disable system shade", "Turns off the stock notification and quick settings pull-down completely, so only HyperCC opens. Needs Shizuku.",
                prefs.disableShade || prefs.hideStockQs, enabled = ShizukuHelper.ready()) {
                prefs.hideStockQs = it   // kunci lama, kekal serasi
                prefs.disableShade = it
                ShizukuHelper.applyStock()
            }
        ))
        page.addView(note("Heads-up banners still appear and work as usual. If HyperCC is turned off (switch at the top) or its accessibility service is turned off, the system shade is restored automatically. Without Shizuku the system shade cannot be disabled; \"Replace system shade\" only closes it after it appears."))

        scroll.removeAllViews()
        scroll.addView(page)
        scroll.post { scroll.scrollTo(0, y) }
    }

    // ================= Pratonton =================

    private fun preview(): View {
        if (prefs.panelStyle != 1) return previewAosp()

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10.dp, 12.dp, 10.dp, 8.dp)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF252525.toInt(), 0xFF171717.toInt(), 0xFF292929.toInt())
            ).apply { cornerRadius = 28.dpf }
            clipToOutline = true
            minimumHeight = 780.dp
        }

        // Status and header follow the same order and spacing as the live HyperOS panel.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp, 0, 4.dp, 10.dp)
        }
        val dateRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        dateRow.addView(android.widget.TextClock(this).apply {
            format12Hour = "EEE, d MMM"; format24Hour = "EEE, d MMM"
            textSize = 15f; setTextColor(Color.WHITE); includeFontPadding = false
        }, LinearLayout.LayoutParams(0, -2, 1f))
        dateRow.addView(ImageView(this).apply {
            setImageDrawable(tinted(R.drawable.ic_edit, Color.WHITE))
            setPadding(5.dp, 5.dp, 5.dp, 5.dp)
        }, LinearLayout.LayoutParams(30.dp, 30.dp))
        dateRow.addView(ImageView(this).apply {
            setImageDrawable(tinted(R.drawable.ic_settings, Color.WHITE))
            setPadding(5.dp, 5.dp, 5.dp, 5.dp)
        }, LinearLayout.LayoutParams(30.dp, 30.dp).apply { marginStart = 4.dp })
        top.addView(dateRow)
        top.addView(text("Wi-Fi connected   •   5G   •   85%", 10.5f, 0xFFD1D1D1.toInt()).apply {
            setPadding(0, 5.dp, 0, 0)
            maxLines = 1
        })
        screen.addView(top)

        val network = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun networkTile(name: String, state: String, icon: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(15.dp, 0, 9.dp, 0)
            background = rippleBg(0xFF347FF2.toInt(), 25.dpf, 0x33FFFFFF)
            addView(ImageView(this@MainActivity).apply {
                setImageDrawable(tinted(icon, Color.WHITE))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(31.dp, 31.dp))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(11.dp, 0, 0, 0)
                addView(text(name, 15f, Color.WHITE, medium = true).apply { maxLines = 1 })
                addView(text(state, 12f, 0xFFDCE8FF.toInt()).apply { setPadding(0, 3.dp, 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        network.addView(networkTile("Airtel Xstream", "Connected", R.drawable.ic_wifi),
            LinearLayout.LayoutParams(0, 82.dp, 1f).apply { marginEnd = 6.dp })
        network.addView(networkTile("Mobile data", "On", R.drawable.ic_data),
            LinearLayout.LayoutParams(0, 82.dp, 1f).apply { marginStart = 6.dp })
        screen.addView(network, LinearLayout.LayoutParams(-1, 82.dp).apply { bottomMargin = 14.dp })

        // The media tile and both vertical sliders use the exact live-panel height.
        val control = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cardHeight = 184
        val media = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(15.dp, 13.dp, 12.dp, 10.dp)
            background = rounded(0xFF111111.toInt(), 23.dpf)
            val cover = TextView(this@MainActivity).apply {
                text = "VISIONS"
                textSize = 8f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFF68499A.toInt(), 0xFF1F2B59.toInt(), 0xFF12121B.toInt())).apply { cornerRadius = 9.dpf }
            }
            addView(cover, LinearLayout.LayoutParams(44.dp, 44.dp))
            addView(text("VISIONS", 14f, Color.WHITE, medium = true).apply { setPadding(0, 11.dp, 0, 0) })
            addView(text("VALORANT, eaJ & Sa...", 11.5f, 0xFFB6B6B6.toInt()).apply { setPadding(0, 4.dp, 0, 0); maxLines = 1 })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(-1, 0, 1f))
            val buttons = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            listOf(R.drawable.ic_media_previous, R.drawable.ic_media_play, R.drawable.ic_media_next).forEach { res ->
                buttons.addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(tinted(res, Color.WHITE))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(5.dp, 4.dp, 5.dp, 4.dp)
                }, LinearLayout.LayoutParams(0, 36.dp, 1f))
            }
            addView(buttons, LinearLayout.LayoutParams(-1, 38.dp).apply { topMargin = 4.dp })
        }
        control.addView(media, LinearLayout.LayoutParams(0, cardHeight.dp, 1f).apply { marginEnd = 7.dp })
        val brightness = M3VerticalSlider(this, pal, R.drawable.ic_brightness, cardHeight, 72,
            Color.WHITE, 0xFF111111.toInt(), 0xFF777D86.toInt(), Color.WHITE) {}.also { it.setSystemValue(.60f) }
        val volume = M3VerticalSlider(this, pal, R.drawable.ic_volume, cardHeight, 72,
            Color.WHITE, 0xFF111111.toInt(), 0xFF777D86.toInt(), Color.WHITE) {}.also { it.setSystemValue(.42f) }
        control.addView(brightness, LinearLayout.LayoutParams(72.dp, cardHeight.dp).apply { marginStart = 7.dp })
        control.addView(volume, LinearLayout.LayoutParams(72.dp, cardHeight.dp).apply { marginStart = 7.dp })
        screen.addView(control, LinearLayout.LayoutParams(-1, cardHeight.dp).apply { bottomMargin = 13.dp })

        // Preview uses the user's actual enabled tile order, just like QuickSettingsUi.
        val mainIds = setOf("internet", "data")
        val previewTiles = prefs.tileOrder()
            .filter { prefs.tileEnabled(it) && it !in mainIds }
            .mapNotNull { id -> TileCatalog.meta(id) }
        val selectedIds = setOf("bluetooth", "rotate", "location", "invert")
        previewTiles.chunked(4).forEach { tileRow ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            tileRow.forEach { meta ->
                val active = meta.id in selectedIds
                val holder = LinearLayout(this).apply {
                    gravity = Gravity.CENTER
                    val icon = ImageView(this@MainActivity).apply {
                        setPadding(20.dp, 20.dp, 20.dp, 20.dp)
                        setImageDrawable(tinted(meta.icon, if (active) Color.WHITE else 0xFFF4F4F4.toInt()))
                        background = rippleBg(if (active) 0xFF347FF2.toInt() else 0xFF111111.toInt(),
                            34.dpf, 0x33FFFFFF)
                    }
                    addView(icon, LinearLayout.LayoutParams(68.dp, 68.dp))
                }
                row.addView(holder, LinearLayout.LayoutParams(0, 80.dp, 1f))
            }
            repeat(4 - tileRow.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 80.dp, 1f)) }
            screen.addView(row, LinearLayout.LayoutParams(-1, 80.dp).apply { bottomMargin = 7.dp })
        }

        screen.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 8.dp, 0, 3.dp)
        }
        nav.addView(text("◀", 15f, 0xFFBDBDBD.toInt()), LinearLayout.LayoutParams(0, 20.dp, 1f).apply { gravity = Gravity.CENTER })
        nav.addView(text("○", 22f, 0xFFBDBDBD.toInt()).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, 20.dp, 1f))
        nav.addView(text("■", 15f, 0xFFBDBDBD.toInt()).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, 20.dp, 1f))
        screen.addView(nav, LinearLayout.LayoutParams(-1, 28.dp))
        return screen
    }

    private fun previewAosp(): View {
        val wallpaper = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(pal.primaryContainer, pal.primary)).apply { cornerRadius = 32.dpf }
        val outer = LinearLayout(this).apply { background = wallpaper; setPadding(18.dp, 18.dp, 18.dp, 18.dp) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            background = rounded(withAlpha(pal.surface, prefs.opacity / 100f), 26.dpf)
        }
        val row = LinearLayout(this)
        row.addView(previewTile("Internet", R.drawable.ic_wifi, true), LinearLayout.LayoutParams(0, 56.dp, 1f).apply { marginEnd = 4.dp })
        row.addView(previewTile("Bluetooth", R.drawable.ic_bluetooth, false), LinearLayout.LayoutParams(0, 56.dp, 1f).apply { marginStart = 4.dp })
        panel.addView(row)
        panel.addView(M3Slider(this, pal, R.drawable.ic_brightness, 44) {}.also { it.setSystemValue(0.6f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
        outer.addView(panel, LinearLayout.LayoutParams(-1, -2))
        return outer
    }

    private fun previewTile(name: String, icon: Int, on: Boolean): View {
        val fg = if (on) pal.onPrimary else pal.onSurface
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 0, 8.dp, 0)
            background = tileBackground(pal, prefs.tileShape, on, 56.dp)
            addView(ImageView(context).apply { setImageDrawable(tinted(icon, fg)) }, LinearLayout.LayoutParams(22.dp, 22.dp))
            addView(text(name, 13f, fg, medium = true).apply { setPadding(10.dp, 0, 0, 0) })
        }
    }

    private fun tryRow(): View {
        fun btn(title: String, kind: Int) = text(title, 14f, pal.onPrimaryContainer, medium = true).apply {
            gravity = Gravity.CENTER
            background = rippleBg(pal.primaryContainer, 24.dpf, withAlpha(pal.onPrimaryContainer, .14f))
            isClickable = true
            setOnClickListener {
                val s = ControlCenterService.instance
                if (!prefs.enabled) Toast.makeText(this@MainActivity, "HyperCC is turned off", Toast.LENGTH_SHORT).show()
                else if (s == null) Toast.makeText(this@MainActivity, "Turn on the accessibility service first", Toast.LENGTH_SHORT).show()
                else s.showPanel(kind)
            }
        }
        return LinearLayout(this).apply {
            addView(btn("Try Quick Settings", ControlCenterService.QS), LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginEnd = 6.dp })
            addView(btn("Try Notifications", ControlCenterService.NOTIF), LinearLayout.LayoutParams(0, 48.dp, 1f).apply { marginStart = 6.dp })
        }
    }

    // ================= Setup =================

    private fun accessibilityOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(":").any { it.startsWith("$packageName/") }
    }

    private fun setupGroup(): View {
        val nm = getSystemService(NotificationManager::class.java)
        val shizuku = when {
            !ShizukuHelper.binderAlive() -> "Optional · not running"
            !ShizukuHelper.granted() -> "Optional · tap to allow"
            ShizukuHelper.ready() -> "Connected"
            else -> "Connecting…"
        }
        return group(
            statusRow(R.drawable.ic_accessibility, "Accessibility service", "Needed for edge swipes", accessibilityOn()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            statusRow(R.drawable.ic_notifications, "Notification access", "Needed for notifications and media player controls",
                HyperNotificationListener.isEnabled(this)) { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            statusRow(R.drawable.ic_settings, "Modify system settings", "Brightness and auto-rotate",
                Settings.System.canWrite(this)) { open(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:$packageName") },
            statusRow(R.drawable.ic_dnd, "Do Not Disturb access", "Needed for the DND tile",
                nm.isNotificationPolicyAccessGranted) { open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
            statusRow(R.drawable.ic_bolt, "Shizuku", shizuku, ShizukuHelper.ready()) {
                if (!ShizukuHelper.binderAlive()) {
                    packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                        ?: open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:moe.shizuku.privileged.api")
                } else ShizukuHelper.requestPermission()
            },
            statusRow(R.drawable.ic_lock, "Can't switch it on?", "Android 13+ blocks sideloaded apps. Open App info, tap ⋮, then Allow restricted settings.", false, showState = false) {
                open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName")
            }
        )
    }

    private fun open(action: String, uri: String? = null) {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!uri.isNullOrBlank()) i.data = Uri.parse(uri)
        try { startActivity(i) } catch (_: Exception) {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    // ================= Editor tile =================

    private fun tileEditor(): View {
        val order = prefs.tileOrder()
        val rows = order.mapIndexed { i, id ->
            val meta = TileCatalog.meta(id)!!
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp, 8.dp, 12.dp, 8.dp)
            }
            row.addView(ImageView(this).apply { setImageDrawable(tinted(meta.icon, pal.onVariant)) }, LinearLayout.LayoutParams(24.dp, 24.dp))
            row.addView(text(meta.label, 16f, pal.onSurface).apply { setPadding(16.dp, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            fun move(delta: Int) {
                val l = order.toMutableList()
                val j = i + delta
                if (j in l.indices) { l[i] = l[j].also { l[j] = l[i] }; prefs.setTileOrder(l); render() }
            }
            row.addView(smallIconBtn(R.drawable.ic_up, i > 0) { move(-1) }, LinearLayout.LayoutParams(36.dp, 36.dp))
            row.addView(smallIconBtn(R.drawable.ic_down, i < order.lastIndex) { move(1) }, LinearLayout.LayoutParams(36.dp, 36.dp))
            row.addView(M3Switch(this, pal).also { s ->
                s.set(prefs.tileEnabled(id), animate = false)
                s.onToggle = { prefs.setTileEnabled(id, it) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 8.dp })
            row
        }
        return group(*rows.toTypedArray())
    }

    private fun smallIconBtn(res: Int, enabled: Boolean, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(tinted(res, pal.onVariant))
        setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        alpha = if (enabled) 1f else 0.25f
        isClickable = enabled
        if (enabled) { background = rippleBg(Color.TRANSPARENT, 18.dpf, withAlpha(pal.onSurface, .14f)); setOnClickListener { onClick() } }
    }

    // ================= Komponen baris =================

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top.dp }

    private fun label(s: String) = text(s, 14f, pal.primary, medium = true).apply { setPadding(20.dp, 28.dp, 0, 10.dp) }

    private fun note(s: String) = text(s, 12.5f, pal.onVariant).apply { setPadding(20.dp, 10.dp, 20.dp, 0); setLineSpacing(0f, 1.1f) }

    private fun group(vararg rows: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        rows.forEachIndexed { i, r ->
            val top = if (i == 0) 28.dpf else 6.dpf
            val bottom = if (i == rows.lastIndex) 28.dpf else 6.dpf
            r.background = rippleBg(pal.container, top, top, bottom, bottom, withAlpha(pal.onSurface, .08f))
            addView(r, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = 2.dp })
        }
    }

    private fun statusRow(icon: Int, title: String, summary: String, on: Boolean, showState: Boolean = true, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dp, 16.dp, 16.dp, 16.dp)
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(ImageView(this).apply { setImageDrawable(tinted(icon, if (on) pal.primary else pal.onVariant)) }, LinearLayout.LayoutParams(24.dp, 24.dp))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp, 0, 8.dp, 0) }
        col.addView(text(title, 16f, pal.onSurface))
        col.addView(text(summary, 13f, pal.onVariant).apply { setPadding(0, 3.dp, 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (showState) {
            row.addView(ImageView(this).apply {
                setImageDrawable(tinted(if (on) R.drawable.ic_check else R.drawable.ic_chevron, if (on) pal.onPrimaryContainer else pal.onVariant))
                if (on) { setPadding(5.dp, 5.dp, 5.dp, 5.dp); background = rounded(pal.primaryContainer, 14.dpf) }
            }, LinearLayout.LayoutParams(28.dp, 28.dp))
        } else {
            row.addView(ImageView(this).apply { setImageDrawable(tinted(R.drawable.ic_chevron, pal.onVariant)) }, LinearLayout.LayoutParams(24.dp, 24.dp))
        }
        return row
    }

    private fun switchRow(title: String, summary: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(20.dp, 16.dp, 16.dp, 16.dp) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 12.dp, 0) }
        col.addView(text(title, 16f, pal.onSurface))
        col.addView(text(summary, 13f, pal.onVariant).apply { setPadding(0, 3.dp, 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = M3Switch(this, pal).also { it.set(value, animate = false); it.onToggle = onChange }
        row.addView(sw)
        if (!enabled) { row.alpha = 0.45f; sw.isEnabled = false; sw.isClickable = false }
        return row
    }

    private fun segmentRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp, 16.dp, 20.dp, 16.dp) }
        col.addView(text(title, 16f, pal.onSurface))
        val seg = LinearLayout(this).apply { setPadding(0, 12.dp, 0, 0) }
        options.forEachIndexed { i, o ->
            val sel = i == selected
            seg.addView(text(o, 13.5f, if (sel) pal.onSecondary else pal.onVariant, medium = true).apply {
                gravity = Gravity.CENTER
                background = rippleBg(if (sel) pal.secondary else pal.high, 20.dpf, withAlpha(pal.onSurface, .12f))
                isClickable = true
                setOnClickListener { if (!sel) onSelect(i) }
            }, LinearLayout.LayoutParams(0, 40.dp, 1f).apply { if (i > 0) marginStart = 6.dp })
        }
        col.addView(seg)
        return col
    }

    private fun sliderRow(title: String, value: Float, fmt: (Float) -> String, onChange: (Float) -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp, 16.dp, 20.dp, 16.dp) }
        val head = LinearLayout(this)
        head.addView(text(title, 16f, pal.onSurface), LinearLayout.LayoutParams(0, -2, 1f))
        val v = text(fmt(value), 14f, pal.onVariant)
        head.addView(v)
        col.addView(head)
        val s = M3Slider(this, pal, null, 36) { x -> v.text = fmt(x); onChange(x) }
        s.setSystemValue(value)
        col.addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
        return col
    }

    private fun colorRow(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20.dp, 16.dp, 20.dp, 16.dp) }
        col.addView(text("Accent color", 16f, pal.onSurface))
        val row = LinearLayout(this).apply { setPadding(0, 12.dp, 0, 0) }
        fun swatch(bg: android.graphics.drawable.Drawable, selected: Boolean, onClick: () -> Unit, fg: Int) {
            val v = ImageView(this).apply {
                background = bg
                if (selected) { setImageDrawable(tinted(R.drawable.ic_check, fg)); setPadding(8.dp, 8.dp, 8.dp, 8.dp) }
                isClickable = true
                setOnClickListener { onClick() }
            }
            row.addView(v, LinearLayout.LayoutParams(40.dp, 40.dp).apply { marginEnd = 10.dp })
        }
        val sweep = GradientDrawable().apply {
            gradientType = GradientDrawable.SWEEP_GRADIENT
            shape = GradientDrawable.OVAL
            colors = intArrayOf(0xFFEA4335.toInt(), 0xFFFBBC05.toInt(), 0xFF34A853.toInt(), 0xFF4285F4.toInt(), 0xFFEA4335.toInt())
        }
        swatch(sweep, prefs.accent == 0, { prefs.accent = 0; render() }, Color.WHITE)
        accents.forEach { (_, c) ->
            swatch(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }, prefs.accent == c, { prefs.accent = c; render() }, Color.WHITE)
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        col.addView(text(if (prefs.accent == 0) "Dynamic: follows your wallpaper (Android 12+)" else "Custom color", 12.5f, pal.onVariant).apply { setPadding(0, 6.dp, 0, 0) })
        return col
    }
}
