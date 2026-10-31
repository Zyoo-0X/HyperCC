package com.example.hypercc

import android.content.ComponentName
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.content.Context
import android.content.ClipData
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.TextClock
import kotlin.math.roundToInt

/** Kandungan panel Quick Settings: jam + tarikh, slider kecerahan, grid tile, slider volum. */
class QuickSettingsUi(
    private val svc: ControlCenterService,
    private val pal: Palette,
    private val prefs: Prefs,
    private val tiles: Tiles,
    startEditing: Boolean = false
) {
    val view = android.widget.FrameLayout(svc)
    private val content = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
    private var detailOverlay: View? = null
    private val updaters = mutableListOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var askedWrite = false
    private val body = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
    private lateinit var editBtn: ImageView
    private var editing = false
    private var headerStatus: TextView? = null
    private var activeMediaController: MediaController? = null

    /** ScrollView aktif (hanya semasa mod edit) supaya gesture tutup panel tak berebut dengan scroll. */
    var activeScroller: android.widget.ScrollView? = null
        private set

    val isEditing: Boolean get() = editing
    val isShowingDetails: Boolean get() = detailOverlay != null

    init { build(); if (startEditing) toggleEdit() }

    fun refresh() {
        updaters.forEach { runCatching { it() } }
        if (prefs.panelStyle == 1) headerStatus?.text = hyperOsStatusLine()
    }

    fun dispose() {
        handler.removeCallbacksAndMessages(null)
        activeMediaController = null
    }

    private fun build() {
        val hyperOs = prefs.panelStyle == 1
        val header = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (hyperOs) 2.dp else 8.dp, 0, 0, if (hyperOs) 10.dp else 16.dp)
        }
        val clock = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        if (hyperOs) {
            clock.addView(TextClock(svc).apply {
                format12Hour = "EEE, d MMM"; format24Hour = "EEE, d MMM"
                textSize = 16f; setTextColor(pal.onSurface); includeFontPadding = false
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            headerStatus = svc.text(hyperOsStatusLine(), 11f, pal.onVariant).apply {
                setPadding(1.dp, 4.dp, 0, 0)
                maxLines = 1
            }
            clock.addView(headerStatus)
        } else {
            clock.addView(TextClock(svc).apply {
                format12Hour = "h:mm"; format24Hour = "HH:mm"
                textSize = 40f; setTextColor(pal.onSurface); includeFontPadding = false
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            })
            clock.addView(TextClock(svc).apply {
                format12Hour = "EEE, d MMM"; format24Hour = "EEE, d MMM"
                textSize = 14f; setTextColor(pal.onVariant); includeFontPadding = false
                setPadding(2.dp, 6.dp, 0, 0)
            })
        }
        header.addView(clock, LinearLayout.LayoutParams(0, -2, 1f))
        editBtn = iconButton(R.drawable.ic_edit) { toggleEdit() }
        val buttonSize = if (hyperOs) 38.dp else 44.dp
        header.addView(editBtn, LinearLayout.LayoutParams(buttonSize, buttonSize))
        header.addView(iconButton(R.drawable.ic_settings) { svc.openSettings(Settings.ACTION_SETTINGS) },
            LinearLayout.LayoutParams(buttonSize, buttonSize).apply { marginStart = if (hyperOs) 5.dp else 8.dp })
        content.addView(header, LinearLayout.LayoutParams(-1, -2))
        content.addView(body, LinearLayout.LayoutParams(-1, -2))
        view.addView(content, android.widget.FrameLayout.LayoutParams(-1, -2))
        buildBody()
    }

    private fun toggleEdit() {
        editing = !editing
        editBtn.setImageDrawable(svc.tinted(if (editing) R.drawable.ic_check else R.drawable.ic_edit, pal.onSurface))
        if (editing) buildEditor() else buildBody()
    }

    // ---------- Mod biasa ----------
    private fun buildBody() {
        updaters.clear()
        body.removeAllViews()
        activeScroller = null
        val view = body
        if (prefs.panelStyle == 1) {
            buildHyperOsBody(view)
            return
        }

        // Kawalan brightness dan sound boleh disusun serta disembunyikan melalui Edit.
        val cr = svc.contentResolver
        val am = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val bright = M3Slider(svc, pal, R.drawable.ic_brightness) { v ->
            if (Settings.System.canWrite(svc)) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (v * 255f).roundToInt().coerceIn(1, 255))
            } else if (!askedWrite) {
                askedWrite = true
                svc.openSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${svc.packageName}")
            }
        }
        val vol = M3Slider(svc, pal, R.drawable.ic_volume) { v ->
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt().coerceIn(0, max), 0)
        }
        prefs.controlOrder().filter { prefs.controlEnabled(it) }.forEach { id ->
            val slider = if (id == "brightness") bright else vol
            view.addView(slider, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = if (id == "sound") 10.dp else 0
                bottomMargin = 6.dp
            })
        }
        updaters += {
            bright.setSystemValue(Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
            vol.setSystemValue(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1))
        }

        // ---- Grid tile ----
        val specs = tiles.enabled(prefs)
        val hyperOs = prefs.panelStyle == 1
        val columns = if (hyperOs) 2 else prefs.tileCols
        if (columns == 2) {
            specs.chunked(2).forEach { pair ->
                val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { i, spec ->
                    row.addView(largeTile(spec), LinearLayout.LayoutParams(0, if (hyperOs) 76.dp else 68.dp, 1f).apply {
                        if (i == 0) marginEnd = 4.dp else marginStart = 4.dp
                    })
                }
                if (pair.size == 1) row.addView(View(svc), LinearLayout.LayoutParams(0, if (hyperOs) 76.dp else 68.dp, 1f).apply { marginStart = 4.dp })
                view.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = if (hyperOs) 10.dp else 8.dp })
            }
        } else {
            specs.chunked(4).forEach { group ->
                val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
                group.forEach { spec -> row.addView(smallTile(spec), LinearLayout.LayoutParams(0, -2, 1f)) }
                repeat(4 - group.size) { row.addView(View(svc), LinearLayout.LayoutParams(0, 1, 1f)) }
                view.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp })
            }
        }

        refresh()
    }

    // ---------- HyperOS Control Center ----------
    private fun buildHyperOsBody(target: LinearLayout) {
        val cr = svc.contentResolver
        val am = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val all = tiles.allSpecs()
        // Dua kad rangkaian ialah bahagian utama Control Center HyperOS dan kekal tersedia.
        val mainTiles = listOf("internet", "data").mapNotNull { id ->
            all.firstOrNull { it.meta.id == id }
        }
        val mainIds = mainTiles.map { it.meta.id }.toSet()

        val networkRow = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        mainTiles.forEachIndexed { index, spec ->
            networkRow.addView(hyperOsMainTile(spec), LinearLayout.LayoutParams(0, 82.dp, 1f).apply {
                if (index == 0) marginEnd = 6.dp else marginStart = 6.dp
            })
        }
        target.addView(networkRow, LinearLayout.LayoutParams(-1, 82.dp).apply { topMargin = 3.dp })

        val brightness = M3VerticalSlider(
            svc, pal, R.drawable.ic_brightness,
            heightDp = 184, widthDp = 72,
            fillColor = android.graphics.Color.WHITE,
            trackColor = if (pal.dark) 0xFF121212.toInt() else 0xFFD7DBE2.toInt(),
            activeIconColor = 0xFF777D86.toInt(),
            inactiveIconColor = if (pal.dark) android.graphics.Color.WHITE else pal.onSurface
        ) { value ->
            if (Settings.System.canWrite(svc)) {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (value * 255f).roundToInt().coerceIn(1, 255))
            } else if (!askedWrite) {
                askedWrite = true
                svc.openSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${svc.packageName}")
            }
        }
        val volume = M3VerticalSlider(
            svc, pal, R.drawable.ic_volume,
            heightDp = 184, widthDp = 72,
            fillColor = android.graphics.Color.WHITE,
            trackColor = if (pal.dark) 0xFF121212.toInt() else 0xFFD7DBE2.toInt(),
            activeIconColor = 0xFF777D86.toInt(),
            inactiveIconColor = if (pal.dark) android.graphics.Color.WHITE else pal.onSurface
        ) { value ->
            val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            runCatching {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (value * maxVolume).roundToInt().coerceIn(0, maxVolume), 0)
            }
        }

        brightness.contentDescription = "Screen brightness"
        volume.contentDescription = "Media volume"
        val controlsRow = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mediaControlHeight = 184.dp
        controlsRow.addView(mediaCard(), LinearLayout.LayoutParams(0, mediaControlHeight, 1f).apply { marginEnd = 7.dp })
        prefs.controlOrder().filter { prefs.controlEnabled(it) }.forEach { id ->
            val slider = if (id == "brightness") brightness else volume
            controlsRow.addView(slider, LinearLayout.LayoutParams(72.dp, mediaControlHeight).apply { marginStart = 7.dp })
        }
        target.addView(controlsRow, LinearLayout.LayoutParams(-1, mediaControlHeight).apply { topMargin = 14.dp })

        updaters += {
            brightness.setSystemValue(Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
            volume.setSystemValue(am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1))
        }

        val shortcuts = tiles.enabled(prefs).filterNot { it.meta.id in mainIds }
        shortcuts.chunked(4).forEach { rowSpecs ->
            val row = LinearLayout(svc).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            rowSpecs.forEachIndexed { index, spec ->
                row.addView(hyperOsCircleTile(spec), LinearLayout.LayoutParams(0, 80.dp, 1f).apply {
                    if (index > 0) marginStart = 3.dp
                    if (index < rowSpecs.lastIndex) marginEnd = 3.dp
                })
            }
            repeat(4 - rowSpecs.size) {
                row.addView(View(svc), LinearLayout.LayoutParams(0, 80.dp, 1f))
            }
            target.addView(row, LinearLayout.LayoutParams(-1, 80.dp).apply { topMargin = 7.dp })
        }
        refresh()
    }

    private fun hyperOsStatusLine(): String {
        val network = runCatching {
            val cm = svc.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "No connection"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi connected"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile network"
                else -> "Connected"
            }
        }.getOrDefault("Connected")
        val battery = runCatching {
            val intent = svc.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra("level", -1) ?: -1
            val scale = intent?.getIntExtra("scale", 100) ?: 100
            if (level >= 0 && scale > 0) "${(level * 100 / scale)}%" else ""
        }.getOrDefault("")
        return listOf(network, battery).filter { it.isNotBlank() }.joinToString("  •  ")
    }


    private fun hyperOsWifiInfo(): Pair<String, String> {
        val wifi = svc.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifi.isWifiEnabled) return "Wi-Fi" to "Off"
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        val ssid = info?.ssid?.removePrefix("\"")?.removeSuffix("\"")
        val connected = info?.supplicantState == android.net.wifi.SupplicantState.COMPLETED &&
            !ssid.isNullOrBlank() && ssid != "<unknown ssid>"
        return if (connected) ssid!! to "Connected" else "Wi-Fi" to "On"
    }

    private fun hyperOsMainTile(spec: TileSpec): View {
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(15.dp, 0, 10.dp, 0)
            isClickable = true
            isFocusable = true
        }
        val icon = ImageView(svc).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
        val texts = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(11.dp, 0, 0, 0)
        }
        val displayName = if (spec.meta.id == "internet") "Wi-Fi" else "Mobile data"
        val title = svc.text(displayName, 15.5f, android.graphics.Color.WHITE, medium = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subtitle = svc.text("", 12.5f, withAlpha(android.graphics.Color.WHITE, .78f)).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(title)
        texts.addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp })
        box.addView(icon, LinearLayout.LayoutParams(31.dp, 31.dp))
        box.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        wire(box, spec)

        var last: Boolean? = null
        var lastSubtitle = ""
        updaters += {
            val on = stateOf(spec)
            val wifiInfo = if (spec.meta.id == "internet") hyperOsWifiInfo() else null
            val currentTitle = if (spec.meta.id == "internet") wifiInfo?.first ?: "Wi-Fi" else displayName
            val sub = when (spec.meta.id) {
                "internet" -> wifiInfo?.second ?: if (on) "On" else "Off"
                "data" -> if (on) "On" else "Off"
                else -> runCatching { spec.subtitle(on) }.getOrDefault(if (on) "On" else "Off")
            }
            if (on != last || sub != lastSubtitle || title.text != currentTitle) {
                last = on
                lastSubtitle = sub
                title.text = currentTitle
                val activeFill = if (prefs.accent == 0) 0xFF347FF2.toInt() else pal.primary
                val inactiveFill = if (pal.dark) 0xFF151515.toInt() else 0xFFE5E8ED.toInt()
                val fg = if (on) android.graphics.Color.WHITE else pal.onSurface
                box.background = rippleBg(if (on) activeFill else inactiveFill, 27.dpf, withAlpha(fg, .15f))
                icon.setImageDrawable(svc.tinted(spec.meta.icon, fg))
                title.setTextColor(fg)
                subtitle.setTextColor(if (on) withAlpha(fg, .82f) else pal.onVariant)
                subtitle.text = sub
            }
        }
        return box
    }

    private fun hyperOsCircleTile(spec: TileSpec): View {
        val holder = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val icon = ImageView(svc).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(20.dp, 20.dp, 20.dp, 20.dp)
            contentDescription = spec.meta.label
        }
        holder.addView(icon, LinearLayout.LayoutParams(68.dp, 68.dp))
        holder.contentDescription = spec.meta.label
        wire(icon, spec)
        var last: Boolean? = null
        updaters += {
            val on = stateOf(spec)
            if (on != last) {
                last = on
                icon.background = rippleBg(if (on) pal.primary else pal.high, 34.dpf,
                    withAlpha(if (on) android.graphics.Color.WHITE else pal.onSurface, .16f))
                icon.setImageDrawable(svc.tinted(spec.meta.icon, if (on) android.graphics.Color.WHITE else pal.onSurface))
            }
        }
        return holder
    }

    private fun mediaCard(): View {
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(15.dp, 13.dp, 12.dp, 10.dp)
            background = rippleBg(if (pal.dark) 0xFF151515.toInt() else 0xFFE8E9EC.toInt(), 23.dpf,
                withAlpha(pal.onSurface, .12f))
        }
        val artwork = ImageView(svc).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(svc.tinted(R.drawable.ic_media, if (pal.dark) android.graphics.Color.WHITE else pal.onSurface))
            background = cornerShape(if (pal.dark) 0xFF292929.toInt() else 0xFFD2D5DA.toInt(), 9.dpf, 9.dpf, 9.dpf, 9.dpf)
            clipToOutline = true
            contentDescription = "Album artwork"
        }
        card.addView(artwork, LinearLayout.LayoutParams(44.dp, 44.dp))

        val title = svc.text("Not playing", 14f, pal.onSurface, medium = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 11.dp, 0, 0)
        }
        val subtitle = svc.text(
            if (HyperNotificationListener.isEnabled(svc)) "Start music in any app" else "Enable Notification access for player controls",
            11.5f, pal.onVariant
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 4.dp, 0, 0)
        }
        card.addView(title)
        card.addView(subtitle)
        card.addView(View(svc), LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun mediaButton(res: Int, description: String, action: () -> Unit): ImageView {
            val button = ImageView(svc).apply {
                setImageDrawable(svc.tinted(res, if (pal.dark) android.graphics.Color.WHITE else pal.onSurface))
                contentDescription = description
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                isClickable = true
                isFocusable = true
                setPadding(5.dp, 4.dp, 5.dp, 4.dp)
                background = rippleBg(android.graphics.Color.TRANSPARENT, 18.dpf, withAlpha(pal.onSurface, .18f))
                setOnClickListener { action() }
            }
            controls.addView(button, LinearLayout.LayoutParams(0, 36.dp, 1f))
            return button
        }
        val previous = mediaButton(R.drawable.ic_media_previous, "Previous track") {
            runCatching { activeMediaController?.transportControls?.skipToPrevious() }
        }
        val playPause = mediaButton(R.drawable.ic_media_play, "Play or pause") {
            val controller = activeMediaController ?: return@mediaButton
            runCatching {
                val state = controller.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
            }
        }
        val next = mediaButton(R.drawable.ic_media_next, "Next track") {
            runCatching { activeMediaController?.transportControls?.skipToNext() }
        }

        // Simpan butang sebagai tag supaya callback media boleh mengemas kini status ikon.
        playPause.tag = "play"
        card.addView(controls, LinearLayout.LayoutParams(-1, 38.dp).apply { topMargin = 4.dp })

        card.setOnClickListener {
            val controller = activeMediaController
            val launch = controller?.packageName?.let { svc.packageManager.getLaunchIntentForPackage(it) }
            if (launch != null) {
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { svc.startActivity(launch) }
            } else if (!HyperNotificationListener.isEnabled(svc)) {
                svc.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
        }

        fun update() = refreshMediaUi(artwork, title, subtitle, playPause, previous, next)
        // The callback is switched whenever Android changes the active media session.
        updateMediaController()
        updaters += {
            updateMediaController()
            update()
        }
        update()
        return card
    }

    private fun updateMediaController() {
        val next = runCatching {
            val manager = svc.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(svc, HyperNotificationListener::class.java))
            sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull { it.metadata != null }
                ?: sessions.firstOrNull { it.playbackState?.state?.let { state -> state != PlaybackState.STATE_NONE } == true }
        }.getOrNull()
        if (next?.sessionToken == activeMediaController?.sessionToken) return
        activeMediaController = next
    }

    private fun refreshMediaUi(
        artwork: ImageView,
        title: TextView,
        subtitle: TextView,
        playPauseRef: ImageView? = null,
        previousRef: ImageView? = null,
        nextRef: ImageView? = null
    ) {
        val controller = activeMediaController
        val metadata = controller?.metadata
        if (controller == null) {
            title.text = "Not playing"
            subtitle.text = if (HyperNotificationListener.isEnabled(svc)) "Start music in any app" else "Enable Notification access for player controls"
            artwork.setImageDrawable(svc.tinted(R.drawable.ic_media, if (pal.dark) android.graphics.Color.WHITE else pal.onSurface))
            playPauseRef?.setImageDrawable(svc.tinted(R.drawable.ic_media_play, if (pal.dark) android.graphics.Color.WHITE else pal.onSurface))
            playPauseRef?.isEnabled = false
            previousRef?.isEnabled = false
            nextRef?.isEnabled = false
            previousRef?.alpha = .35f
            nextRef?.alpha = .35f
            return
        }

        val titleText = metadata?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() } ?: appName(controller.packageName)
        val artistText = metadata?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: metadata?.getText(MediaMetadata.METADATA_KEY_ALBUM)?.toString()?.takeIf { it.isNotBlank() }
            ?: appName(controller.packageName)
        title.text = titleText
        subtitle.text = artistText

        val art = runCatching {
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }.getOrNull()
        if (art != null) artwork.setImageDrawable(BitmapDrawable(svc.resources, art))
        else artwork.setImageDrawable(svc.tinted(R.drawable.ic_media, if (pal.dark) android.graphics.Color.WHITE else pal.onSurface))

        val state = controller.playbackState?.state
        val playing = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        playPauseRef?.setImageDrawable(svc.tinted(
            if (playing) R.drawable.ic_media_pause else R.drawable.ic_media_play,
            if (pal.dark) android.graphics.Color.WHITE else pal.onSurface
        ))
        val actions = controller.playbackState?.actions ?: 0L
        val canPrevious = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
        val canNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
        previousRef?.isEnabled = canPrevious
        previousRef?.alpha = if (canPrevious) 1f else .35f
        nextRef?.isEnabled = canNext
        nextRef?.alpha = if (canNext) 1f else .35f
        playPauseRef?.isEnabled = true
        playPauseRef?.alpha = 1f
    }

    private fun appName(packageName: String): String = runCatching {
        svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    // ---------- Editor Quick Settings gaya Android ----------
    private fun buildEditor() {
        updaters.clear()
        body.removeAllViews()

        val scroller = MaxHeightScrollView(svc, (svc.resources.displayMetrics.heightPixels * 0.62f).toInt())
        val content = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp, 0, 4.dp, 8.dp)
        }

        content.addView(svc.text("Edit", 22f, pal.onSurface, medium = true).apply {
            setPadding(8.dp, 4.dp, 0, 4.dp)
        })
        content.addView(svc.text("Touch and hold a tile to rearrange it. Wi-Fi and Mobile data stay in the top cards.", 13f, pal.onVariant).apply {
            setPadding(8.dp, 0, 8.dp, 16.dp)
        })

        val activeLabel = svc.text("ACTIVE TILES", 12f, pal.onVariant, medium = true).apply {
            setPadding(8.dp, 8.dp, 0, 8.dp)
        }
        content.addView(activeLabel)
        val activeGrid = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        val availableLabel = svc.text("AVAILABLE TILES", 12f, pal.onVariant, medium = true).apply {
            setPadding(8.dp, 20.dp, 0, 8.dp)
        }
        val availableGrid = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }

        fun refreshLists() {
            activeGrid.removeAllViews()
            availableGrid.removeAllViews()
            val order = prefs.tileOrder().filterNot { it == "internet" || it == "data" }
            val active = order.filter { prefs.tileEnabled(it) }
            val available = order.filterNot { prefs.tileEnabled(it) }

            fun populate(target: LinearLayout, ids: List<String>, isActive: Boolean) {
                ids.chunked(2).forEach { pair ->
                    val line = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
                    pair.forEachIndexed { index, id ->
                        val meta = TileCatalog.meta(id) ?: return@forEachIndexed
                        val card = LinearLayout(svc).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(12.dp, 10.dp, 10.dp, 10.dp)
                            background = rippleBg(pal.container, 18.dpf, withAlpha(pal.onSurface, .10f))
                            isClickable = true
                            isLongClickable = true
                            contentDescription = if (isActive) "${meta.label}, drag to rearrange or remove" else "${meta.label}, drag to add"
                            setOnLongClickListener { v ->
                                val clip = ClipData.newPlainText("tile", id)
                                v.startDragAndDrop(clip, View.DragShadowBuilder(v), id, 0)
                            }
                        }
                        card.addView(ImageView(svc).apply {
                            setImageDrawable(svc.tinted(meta.icon, if (isActive) pal.primary else pal.onVariant))
                        }, LinearLayout.LayoutParams(24.dp, 24.dp))
                        card.addView(svc.text(meta.label, 13f, pal.onSurface, medium = true).apply {
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(9.dp, 0, 2.dp, 0)
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                        card.addView(svc.text(if (isActive) "−" else "+", 24f, pal.onVariant, medium = true).apply {
                            gravity = Gravity.CENTER
                            setPadding(5.dp, 0, 2.dp, 0)
                            isClickable = true
                            setOnClickListener {
                                prefs.setTileEnabled(id, !isActive)
                                refreshLists()
                            }
                        }, LinearLayout.LayoutParams(28.dp, 36.dp))
                        line.addView(card, LinearLayout.LayoutParams(0, 64.dp, 1f).apply {
                            if (index == 0) marginEnd = 4.dp else marginStart = 4.dp
                        })
                    }
                    if (pair.size == 1) line.addView(View(svc), LinearLayout.LayoutParams(0, 64.dp, 1f).apply { marginStart = 4.dp })
                    target.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp })
                }
            }

            populate(activeGrid, active, true)
            populate(availableGrid, available, false)
        }

        fun installDropTarget(target: LinearLayout, receivesActive: Boolean) {
            target.setOnDragListener { v, event ->
                when (event.action) {
                    android.view.DragEvent.ACTION_DRAG_STARTED -> true
                    android.view.DragEvent.ACTION_DRAG_ENTERED -> { v.alpha = 0.78f; true }
                    android.view.DragEvent.ACTION_DRAG_EXITED -> { v.alpha = 1f; true }
                    android.view.DragEvent.ACTION_DROP -> {
                        v.alpha = 1f
                        val id = event.localState as? String ?: return@setOnDragListener false
                        if (TileCatalog.meta(id) == null) return@setOnDragListener false
                        if (receivesActive) {
                            prefs.setTileEnabled(id, true)
                            val order = prefs.tileOrder().filterNot { it == "internet" || it == "data" }.toMutableList()
                            order.remove(id)
                            val activeCount = order.count { prefs.tileEnabled(it) }
                            val targetRow = (event.y / 68.dp.toFloat()).toInt().coerceAtLeast(0)
                            val targetColumn = if (event.x >= v.width / 2f) 1 else 0
                            val targetIndex = (targetRow * 2 + targetColumn).coerceIn(0, activeCount)
                            val activeIds = order.filter { prefs.tileEnabled(it) }.toMutableList()
                            activeIds.add(targetIndex.coerceAtMost(activeIds.size), id)
                            val inactiveIds = order.filterNot { prefs.tileEnabled(it) }
                            prefs.setTileOrder(activeIds + inactiveIds)
                        } else {
                            prefs.setTileEnabled(id, false)
                        }
                        refreshLists()
                        true
                    }
                    android.view.DragEvent.ACTION_DRAG_ENDED -> { v.alpha = 1f; true }
                    else -> true
                }
            }
        }
        installDropTarget(activeGrid, true)
        installDropTarget(availableGrid, false)
        content.addView(activeGrid)
        content.addView(availableLabel)
        content.addView(availableGrid)

        content.addView(svc.text("CONTROLS", 12f, pal.onVariant, medium = true).apply {
            setPadding(8.dp, 22.dp, 0, 8.dp)
        })
        content.addView(svc.text("Show, hide, or drag brightness and sound to change their position.", 13f, pal.onVariant).apply {
            setPadding(8.dp, 0, 8.dp, 8.dp)
        })
        val controlsGrid = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
        fun refreshControls() {
            controlsGrid.removeAllViews()
            val order = prefs.controlOrder()
            order.chunked(2).forEach { pair ->
                val line = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { index, id ->
                    val active = prefs.controlEnabled(id)
                    val isBright = id == "brightness"
                    val label = if (isBright) "Brightness" else "Sound"
                    val icon = if (isBright) R.drawable.ic_brightness else R.drawable.ic_volume
                    val card = LinearLayout(svc).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(12.dp, 10.dp, 10.dp, 10.dp)
                        background = rippleBg(pal.container, 18.dpf, withAlpha(pal.onSurface, .10f))
                        isClickable = true
                        isLongClickable = true
                        contentDescription = "$label, drag to rearrange"
                        setOnLongClickListener { v ->
                            val clip = ClipData.newPlainText("control", id)
                            v.startDragAndDrop(clip, View.DragShadowBuilder(v), id, 0)
                        }
                    }
                    card.addView(ImageView(svc).apply { setImageDrawable(svc.tinted(icon, if (active) pal.primary else pal.onVariant)) }, LinearLayout.LayoutParams(24.dp, 24.dp))
                    card.addView(svc.text(label, 13f, pal.onSurface, medium = true).apply { setPadding(9.dp, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                    card.addView(svc.text(if (active) "−" else "+", 24f, pal.onVariant, medium = true).apply {
                        gravity = Gravity.CENTER
                        setOnClickListener { prefs.setControlEnabled(id, !active); refreshControls() }
                    }, LinearLayout.LayoutParams(28.dp, 36.dp))
                    line.addView(card, LinearLayout.LayoutParams(0, 60.dp, 1f).apply { if (index == 0) marginEnd = 4.dp else marginStart = 4.dp })
                }
                if (pair.size == 1) line.addView(View(svc), LinearLayout.LayoutParams(0, 60.dp, 1f).apply { marginStart = 4.dp })
                controlsGrid.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 4.dp })
            }
        }
        controlsGrid.setOnDragListener { v, event ->
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> true
                android.view.DragEvent.ACTION_DROP -> {
                    val id = event.localState as? String ?: return@setOnDragListener false
                    if (id !in listOf("brightness", "sound")) return@setOnDragListener false
                    val order = prefs.controlOrder().toMutableList()
                    order.remove(id)
                    val targetIndex = if (event.x >= v.width / 2f) 1 else 0
                    order.add(targetIndex.coerceIn(0, order.size), id)
                    prefs.setControlOrder(order)
                    refreshControls()
                    true
                }
                else -> true
            }
        }
        content.addView(controlsGrid)
        refreshControls()

        content.addView(svc.text("APPEARANCE", 12f, pal.onVariant, medium = true).apply {
            setPadding(8.dp, 22.dp, 0, 8.dp)
        })
        content.addView(appearanceCard())

        scroller.addView(content)
        activeScroller = scroller
        body.addView(scroller, LinearLayout.LayoutParams(-1, -2))

        body.addView(svc.text("More settings", 14f, pal.onPrimaryContainer, medium = true).apply {
            gravity = Gravity.CENTER
            background = rippleBg(pal.primaryContainer, 22.dpf, withAlpha(pal.onPrimaryContainer, .14f))
            isClickable = true
            setOnClickListener { svc.openApp() }
        }, LinearLayout.LayoutParams(-1, 44.dp).apply { topMargin = 10.dp })
        refreshLists()
    }

    private val accents = listOf(
        0xFF4285F4.toInt(), 0xFF00A6A6.toInt(), 0xFF2E9E5B.toInt(), 0xFFD9A400.toInt(),
        0xFFF08C00.toInt(), 0xFFE5484D.toInt(), 0xFFE0489A.toInt(), 0xFF8E5CF7.toInt()
    )

    /** Pilihan rupa di dalam mod edit. Bentuk/saiz tile terus nampak; tema & warna membuka semula panel dalam mod edit. */
    private fun appearanceCard(): View {
        val col = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 14.dp, 16.dp, 16.dp)
            background = cornerShape(pal.container, 22.dpf, 22.dpf, 22.dpf, 22.dpf)
        }
        col.addView(tilePreview())
        if (prefs.panelStyle != 1) {
            col.addView(segment("Tile shape", listOf("Dynamic", "Pill", "Rounded"), prefs.tileShape) { prefs.tileShape = it; buildEditor() })
            col.addView(segment("Tile size", listOf("Large", "Small"), if (prefs.tileCols == 4) 1 else 0) {
                prefs.tileCols = if (it == 1) 4 else 2; buildEditor()
            })
        }
        col.addView(segment("Theme", listOf("Auto", "Light", "Dark"), prefs.theme) { prefs.theme = it; svc.reopenEditing() })
        col.addView(svc.text("Accent color", 14f, pal.onSurface).apply { setPadding(0, 14.dp, 0, 8.dp) })
        val row = LinearLayout(svc)
        fun swatch(bg: android.graphics.drawable.Drawable, selected: Boolean, value: Int) {
            val v = ImageView(svc).apply {
                background = bg
                if (selected) { setImageDrawable(svc.tinted(R.drawable.ic_check, android.graphics.Color.WHITE)); setPadding(7.dp, 7.dp, 7.dp, 7.dp) }
                isClickable = true
                setOnClickListener { prefs.accent = value; svc.reopenEditing() }
            }
            row.addView(v, LinearLayout.LayoutParams(36.dp, 36.dp).apply { marginEnd = 10.dp })
        }
        val sweep = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.SWEEP_GRADIENT
            shape = android.graphics.drawable.GradientDrawable.OVAL
            colors = intArrayOf(0xFFEA4335.toInt(), 0xFFFBBC05.toInt(), 0xFF34A853.toInt(), 0xFF4285F4.toInt(), 0xFFEA4335.toInt())
        }
        swatch(sweep, prefs.accent == 0, 0)
        accents.forEach { c ->
            swatch(android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c) }, prefs.accent == c, c)
        }
        col.addView(android.widget.HorizontalScrollView(svc).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        return col
    }

    private fun tilePreview(): View {
        val preview = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 10.dp)
        }
        fun big(name: String, icon: Int, on: Boolean) = LinearLayout(svc).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 0, 8.dp, 0)
            val fill = if (on) pal.primary else pal.container
            val fg = if (on) android.graphics.Color.WHITE else pal.onSurface
            background = rippleBg(fill, 18.dpf, withAlpha(fg, .13f))
            addView(ImageView(svc).apply { setImageDrawable(svc.tinted(icon, fg)) }, LinearLayout.LayoutParams(22.dp, 22.dp))
            addView(svc.text(name, 13f, fg, medium = true).apply { setPadding(10.dp, 0, 0, 0) })
        }
        val network = LinearLayout(svc)
        network.addView(big(if (prefs.panelStyle == 1) "Wi-Fi" else "Internet", R.drawable.ic_wifi, true),
            LinearLayout.LayoutParams(0, 52.dp, 1f).apply { marginEnd = 4.dp })
        network.addView(big(if (prefs.panelStyle == 1) "Mobile data" else "Bluetooth",
            if (prefs.panelStyle == 1) R.drawable.ic_data else R.drawable.ic_bluetooth, false),
            LinearLayout.LayoutParams(0, 52.dp, 1f).apply { marginStart = 4.dp })
        preview.addView(network)

        if (prefs.panelStyle == 1) {
            val circles = LinearLayout(svc).apply { gravity = Gravity.CENTER; setPadding(0, 9.dp, 0, 0) }
            listOf(R.drawable.ic_bluetooth, R.drawable.ic_airplane, R.drawable.ic_dnd, R.drawable.ic_flashlight).forEachIndexed { index, icon ->
                val on = index == 0
                val fg = if (on) android.graphics.Color.WHITE else pal.onSurface
                circles.addView(ImageView(svc).apply {
                    setPadding(13.dp, 13.dp, 13.dp, 13.dp)
                    setImageDrawable(svc.tinted(icon, fg))
                    background = rippleBg(if (on) pal.primary else pal.high, 23.dpf, withAlpha(fg, .14f))
                }, LinearLayout.LayoutParams(46.dp, 46.dp).apply { if (index > 0) marginStart = 9.dp })
            }
            preview.addView(circles)
        } else if (prefs.tileCols == 2) {
            preview.addView(M3Slider(svc, pal, R.drawable.ic_brightness, 44) {}.also { it.setSystemValue(0.6f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
        } else {
            preview.addView(M3Slider(svc, pal, R.drawable.ic_brightness, 44) {}.also { it.setSystemValue(0.6f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8.dp })
        }
        return preview
    }

    private fun segment(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8.dp, 0, 4.dp) }
        col.addView(svc.text(title, 14f, pal.onSurface))
        val seg = LinearLayout(svc).apply { setPadding(0, 8.dp, 0, 0) }
        options.forEachIndexed { i, o ->
            val sel = i == selected
            seg.addView(svc.text(o, 13f, if (sel) pal.onSecondary else pal.onVariant, medium = true).apply {
                gravity = Gravity.CENTER
                background = rippleBg(if (sel) pal.secondary else pal.high, 18.dpf, withAlpha(pal.onSurface, .12f))
                isClickable = true
                setOnClickListener { if (!sel) onSelect(i) }
            }, LinearLayout.LayoutParams(0, 38.dp, 1f).apply { if (i > 0) marginStart = 6.dp })
        }
        col.addView(seg)
        return col
    }

    private fun arrow(res: Int, enabled: Boolean, onClick: () -> Unit) = ImageView(svc).apply {
        setImageDrawable(svc.tinted(res, pal.onVariant))
        setPadding(8.dp, 8.dp, 8.dp, 8.dp)
        alpha = if (enabled) 1f else 0.25f
        isClickable = enabled
        if (enabled) setOnClickListener { onClick() }
    }

    private fun iconButton(res: Int, onClick: () -> Unit) = ImageView(svc).apply {
        setImageDrawable(svc.tinted(res, pal.onSurface))
        setPadding(11.dp, 11.dp, 11.dp, 11.dp)
        background = rippleBg(pal.container, 22.dpf, withAlpha(pal.onSurface, .14f))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun wire(v: View, spec: TileSpec) {
        v.isClickable = true
        v.isFocusable = true
        v.setOnClickListener {
            if (prefs.haptics) v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (prefs.panelStyle == 1) {
                showTileDetails(spec)
            } else {
                runCatching { spec.onTap() }
                handler.postDelayed({ refresh() }, 250)
                handler.postDelayed({ refresh() }, 1000)
            }
        }
        v.setOnLongClickListener {
            if (prefs.panelStyle == 1) showTileDetails(spec) else runCatching { spec.onLong() }
            true
        }
    }

    /** HyperOS tiles open HyperCC's own detail sheet instead of the stock Settings page. */
    private fun showTileDetails(spec: TileSpec) {
        detailOverlay?.let { view.removeView(it) }
        val overlay = android.widget.FrameLayout(svc).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            setOnClickListener { dismissTileDetails() }
        }
        val sheet = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 20.dp, 22.dp, 22.dp)
            background = cornerShape(if (pal.dark) 0xFF202124.toInt() else 0xFFF2F3F5.toInt(), 28.dpf, 28.dpf, 0f, 0f)
            isClickable = true
            elevation = 18.dpf
        }
        val heading = LinearLayout(svc).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(svc.text("‹", 32f, pal.onSurface, light = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { dismissTileDetails() }
        }, LinearLayout.LayoutParams(44.dp, 44.dp))
        heading.addView(svc.text(spec.meta.label, 21f, pal.onSurface, medium = true),
            LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(svc.text("×", 26f, pal.onVariant).apply {
            gravity = Gravity.CENTER
            setOnClickListener { dismissTileDetails() }
        }, LinearLayout.LayoutParams(40.dp, 44.dp))
        sheet.addView(heading)

        val stateText = svc.text("", 14f, pal.onVariant).apply { setPadding(4.dp, 5.dp, 4.dp, 14.dp) }
        sheet.addView(stateText)

        val action = svc.text("", 15f, android.graphics.Color.WHITE, medium = true).apply {
            gravity = Gravity.CENTER
            background = rippleBg(0xFF347FF2.toInt(), 24.dpf, 0x33FFFFFF)
            setPadding(18.dp, 14.dp, 18.dp, 14.dp)
            isClickable = true
        }
        sheet.addView(action, LinearLayout.LayoutParams(-1, 52.dp).apply { bottomMargin = 14.dp })

        fun updateState() {
            val isOn = stateOf(spec)
            val sub = runCatching { spec.subtitle(isOn) }.getOrDefault(if (isOn) "On" else "Off")
            stateText.text = when (spec.meta.id) {
                "internet" -> "${if (isOn) "Wi-Fi is on" else "Wi-Fi is off"}  ·  $sub"
                "data" -> if (isOn) "Mobile data is on" else "Mobile data is off"
                else -> "$sub  ·  ${if (isOn) "On" else "Off"}"
            }
            action.text = when (spec.meta.id) {
                "screenshot" -> "Take screenshot"
                "lock" -> "Lock device"
                "power" -> "Open power menu"
                else -> if (isOn) "Turn off" else "Turn on"
            }
        }
        action.setOnClickListener {
            val allowed = runCatching { tiles.toggleForDetails(spec) }.getOrDefault(false)
            if (!allowed) {
                stateText.text = tiles.detailAccessHint(spec.meta.id)
            } else {
                stateText.text = "Updating…"
                handler.postDelayed({ updateState(); refresh() }, 450)
            }
        }

        when (spec.meta.id) {
            "internet" -> buildWifiDetails(sheet, stateText)
            "data" -> buildMobileDataDetails(sheet)
            "bluetooth" -> buildBluetoothDetails(sheet)
            else -> {
                val description = when (spec.meta.id) {
                    "dnd" -> "Silence calls and alerts while Do Not Disturb is active."
                    "flashlight" -> "Use the phone's camera flash as a torch."
                    "rotate" -> "Allow the screen to rotate automatically."
                    "battery" -> "Reduce background activity to save battery power."
                    "dark" -> "Use the device's dark appearance."
                    "airplane" -> "Turn wireless radios into airplane mode."
                    "location" -> "Control the device location switch."
                    "camera_off" -> "Block camera access across apps. Requires Shizuku on supported Android versions."
                    "mic_off" -> "Block microphone access across apps. Requires Shizuku on supported Android versions."
                    "nfc" -> "Control NFC for contactless and device connections."
                    "sound" -> "Cycle between sound, vibrate and silent modes."
                    "invert" -> "Invert display colours for accessibility."
                    "screenshot" -> "Capture the current screen after closing this panel."
                    "lock" -> "Lock the device screen."
                    "power" -> "Show the Android power menu."
                    else -> "Control this setting from the HyperCC panel."
                }
                sheet.addView(svc.text(description, 14f, pal.onVariant).apply {
                    setPadding(4.dp, 4.dp, 4.dp, 18.dp)
                })
            }
        }

        updateState()
        overlay.addView(sheet, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            leftMargin = 8.dp; rightMargin = 8.dp; topMargin = 64.dp
        })
        view.addView(overlay, android.widget.FrameLayout.LayoutParams(-1, -1))
        detailOverlay = overlay
    }

    private fun dismissTileDetails() {
        detailOverlay?.let { view.removeView(it) }
        detailOverlay = null
    }

    private fun detailRow(title: String, subtitle: String, iconRes: Int = R.drawable.ic_wifi): View {
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 12.dp, 14.dp, 12.dp)
            background = rounded(if (pal.dark) 0xFF2B2C30.toInt() else 0xFFE4E6EA.toInt(), 18.dpf)
        }
        row.addView(ImageView(svc).apply { setImageDrawable(svc.tinted(iconRes, pal.onSurface)) },
            LinearLayout.LayoutParams(24.dp, 24.dp))
        row.addView(LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 0, 0, 0)
            addView(svc.text(title, 14f, pal.onSurface, medium = true))
            addView(svc.text(subtitle, 12f, pal.onVariant).apply { setPadding(0, 3.dp, 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun buildWifiDetails(sheet: LinearLayout, stateText: TextView) {
        val wifi = svc.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        val ssid = runCatching { wifi.connectionInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"") }
            .getOrNull()?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: "Connected network unavailable"
        sheet.addView(svc.text("CURRENT NETWORK", 11f, pal.onVariant, medium = true).apply {
            setPadding(4.dp, 7.dp, 4.dp, 7.dp)
        })
        sheet.addView(detailRow(ssid, if (wifi.isWifiEnabled) "Connected / Wi-Fi enabled" else "Wi-Fi disabled"))
        sheet.addView(svc.text("AVAILABLE NETWORKS", 11f, pal.onVariant, medium = true).apply {
            setPadding(4.dp, 18.dp, 4.dp, 7.dp)
        })
        val results = runCatching { wifi.scanResults.orEmpty().filter { it.SSID.isNotBlank() }.distinctBy { it.SSID }.sortedByDescending { it.level }.take(5) }
            .getOrDefault(emptyList())
        if (results.isEmpty()) {
            sheet.addView(svc.text(
                "Nearby networks will appear here when Android allows Wi-Fi scanning. On Android 13+, grant Nearby Wi-Fi and Location permissions to HyperCC.",
                13f, pal.onVariant
            ).apply { setPadding(4.dp, 4.dp, 4.dp, 12.dp) })
        } else {
            results.forEach { result ->
                val level = android.net.wifi.WifiManager.calculateSignalLevel(result.level, 5)
                sheet.addView(detailRow(result.SSID, "Signal ${level + 1}/5  ·  ${result.frequency} MHz"),
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4.dp })
            }
        }
        val allowScan = svc.text("Allow nearby Wi-Fi access", 13f, 0xFF347FF2.toInt(), medium = true).apply {
            setPadding(4.dp, 12.dp, 4.dp, 5.dp)
            isClickable = true
            setOnClickListener {
                val intent = android.content.Intent(svc, MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(MainActivity.EXTRA_REQUEST_WIFI_PERMISSIONS, true)
                }
                runCatching { svc.startActivity(intent) }
            }
        }
        sheet.addView(allowScan)
        val refresh = svc.text("Refresh networks", 13f, 0xFF347FF2.toInt(), medium = true).apply {
            setPadding(4.dp, 5.dp, 4.dp, 8.dp)
            isClickable = true
            setOnClickListener {
                val started = runCatching { wifi.startScan() }.getOrDefault(false)
                stateText.text = if (started) "Scanning for nearby networks…" else
                    "Scan unavailable. Check Wi-Fi, Location and Nearby Wi-Fi permission."
                handler.postDelayed({ showTileDetails(tiles.allSpecs().first { it.meta.id == "internet" }) }, 1600)
            }
        }
        sheet.addView(refresh)
    }

    private fun buildMobileDataDetails(sheet: LinearLayout) {
        val cm = svc.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val carrierState = if (caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true)
            "Mobile network is the active connection" else "Mobile network is not the active connection"
        sheet.addView(detailRow("Mobile network", carrierState, R.drawable.ic_data))
        sheet.addView(svc.text(
            "Mobile data switching is restricted on standard Android builds. Connect Shizuku to enable direct control from this panel.",
            13f, pal.onVariant
        ).apply { setPadding(4.dp, 12.dp, 4.dp, 4.dp) })
    }

    private fun buildBluetoothDetails(sheet: LinearLayout) {
        val adapter = runCatching { (svc.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter }.getOrNull()
        val state = when (adapter?.state) {
            android.bluetooth.BluetoothAdapter.STATE_ON -> "Bluetooth is on"
            android.bluetooth.BluetoothAdapter.STATE_TURNING_ON -> "Turning on"
            android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF -> "Turning off"
            else -> "Bluetooth is off"
        }
        sheet.addView(detailRow("Bluetooth status", state, R.drawable.ic_bluetooth))
        sheet.addView(svc.text(
            "Pairing and radio control are shown in this HyperCC sheet. Direct radio switching on recent Android versions requires Shizuku.",
            13f, pal.onVariant
        ).apply { setPadding(4.dp, 12.dp, 4.dp, 4.dp) })
    }

    private fun stateOf(spec: TileSpec) = runCatching { spec.isOn() }.getOrDefault(false)

    private fun largeTile(spec: TileSpec): View {
        val hyperOs = prefs.panelStyle == 1
        val h = if (hyperOs) 76.dp else 68.dp
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(if (hyperOs) 16.dp else 18.dp, 0, 12.dp, 0)
        }
        val icon = ImageView(svc)
        val label = svc.text(spec.meta.label, if (hyperOs) 15f else 14f, pal.onSurface, medium = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val sub = svc.text("", 12f, pal.onVariant).apply { maxLines = 1 }
        val texts = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 0, 0, 0)
            addView(label)
            addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 3.dp })
        }
        box.addView(icon, LinearLayout.LayoutParams(26.dp, 26.dp))
        box.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        wire(box, spec)

        var last: Boolean? = null
        var lastSub = ""
        updaters += {
            val on = stateOf(spec)
            val st = runCatching { spec.subtitle(on) }.getOrDefault("")
            if (on != last || st != lastSub) {
                last = on
                lastSub = st
                box.background = if (hyperOs) {
                    val fill = if (on) pal.primary else pal.container
                    rippleBg(fill, 22.dpf, withAlpha(if (on) pal.onPrimary else pal.onSurface, .13f))
                } else tileBackground(pal, prefs.tileShape, on, h)
                icon.setImageDrawable(svc.tinted(spec.meta.icon, if (on) pal.onPrimary else pal.onSurface))
                label.setTextColor(if (on) pal.onPrimary else pal.onSurface)
                sub.setTextColor(if (on) withAlpha(pal.onPrimary, .8f) else pal.onVariant)
                sub.text = spec.subtitle(on)
            }
        }
        return box
    }

    private fun smallTile(spec: TileSpec): View {
        val size = 60.dp
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val circle = ImageView(svc).apply { setPadding(18.dp, 18.dp, 18.dp, 18.dp) }
        val name = svc.text(spec.meta.label, 11f, pal.onVariant).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        }
        col.addView(circle, LinearLayout.LayoutParams(size, size))
        col.addView(name, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6.dp })
        wire(circle, spec)

        var last: Boolean? = null
        updaters += {
            val on = stateOf(spec)
            if (on != last) {
                last = on
                circle.background = tileBackground(pal, prefs.tileShape, on, size, circleOnly = true)
                circle.setImageDrawable(svc.tinted(spec.meta.icon, if (on) pal.onPrimary else pal.onSurface))
            }
        }
        return col
    }
}
