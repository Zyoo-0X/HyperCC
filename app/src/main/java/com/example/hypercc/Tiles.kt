package com.example.hypercc

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.nfc.NfcAdapter
import android.accessibilityservice.AccessibilityService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

/** Senarai tile yang tersedia (digunakan oleh panel dan oleh skrin tetapan). */
object TileCatalog {
    class Meta(val id: String, val label: String, val icon: Int, val defaultOn: Boolean)

    val all = listOf(
        Meta("internet", "Internet", R.drawable.ic_wifi, true),
        Meta("bluetooth", "Bluetooth", R.drawable.ic_bluetooth, true),
        Meta("dnd", "Do Not Disturb", R.drawable.ic_dnd, true),
        Meta("flashlight", "Flashlight", R.drawable.ic_flashlight, true),
        Meta("rotate", "Auto-rotate", R.drawable.ic_rotate, true),
        Meta("battery", "Battery Saver", R.drawable.ic_battery_saver, true),
        Meta("dark", "Dark theme", R.drawable.ic_dark_mode, true),
        Meta("airplane", "Airplane mode", R.drawable.ic_airplane, true),
        Meta("location", "Location", R.drawable.ic_location, true),
        Meta("data", "Mobile data", R.drawable.ic_data, true),
        Meta("camera_off", "Camera off", R.drawable.ic_camera_off, false),
        Meta("mic_off", "Mic off", R.drawable.ic_mic_off, false),
        Meta("nfc", "NFC", R.drawable.ic_nfc, true),
        Meta("sound", "Sound mode", R.drawable.ic_volume, true),
        Meta("invert", "Color inversion", R.drawable.ic_invert, true),
        Meta("screenshot", "Screenshot", R.drawable.ic_screenshot, true),
        Meta("lock", "Lock screen", R.drawable.ic_lock, true),
        Meta("power", "Power menu", R.drawable.ic_power, true),
    )

    fun meta(id: String) = all.firstOrNull { it.id == id }
}

class TileSpec(
    val meta: TileCatalog.Meta,
    val isOn: () -> Boolean,
    val onTap: () -> Unit,
    val onLong: () -> Unit,
    val subtitle: (Boolean) -> String = { if (it) "On" else "Off" }
)

/** Logik setiap tile. Toggle sebenar guna Shizuku; tanpa Shizuku, buka halaman tetapan berkaitan. */
class Tiles(private val svc: ControlCenterService) {
    private val cr get() = svc.contentResolver
    private val cm = svc.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var torchId: String? = null
    @Volatile private var torchOn = false

    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) torchOn = enabled
        }
    }

    fun start() {
        torchId = runCatching {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        }.getOrNull()
        runCatching { cm.registerTorchCallback(torchCb, Handler(Looper.getMainLooper())) }
    }

    fun stop() { runCatching { cm.unregisterTorchCallback(torchCb) } }

    private fun toggleVia(on: String, off: String, isOn: Boolean, fallback: () -> Unit) {
        if (ShizukuHelper.ready()) ShizukuHelper.run(if (isOn) off else on) else fallback()
    }

    private val wifi get() = svc.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val nm get() = svc.getSystemService(NotificationManager::class.java)

    private fun btOn() = runCatching {
        (svc.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
    }.getOrDefault(false)

    private fun globalInt(key: String) = Settings.Global.getInt(cr, key, 0)

    private val specs: List<TileSpec> by lazy {
        listOf(
            TileSpec(TileCatalog.meta("internet")!!,
                isOn = { wifi.isWifiEnabled },
                onTap = {
                    toggleVia("svc wifi enable", "svc wifi disable", wifi.isWifiEnabled) {
                        if (Build.VERSION.SDK_INT >= 29) svc.openSettings(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                        else svc.openSettings(Settings.ACTION_WIFI_SETTINGS)
                    }
                },
                onLong = { svc.openSettings(Settings.ACTION_WIFI_SETTINGS) }),

            TileSpec(TileCatalog.meta("bluetooth")!!,
                isOn = { btOn() },
                onTap = {
                    toggleVia("svc bluetooth enable || cmd bluetooth_manager enable",
                        "svc bluetooth disable || cmd bluetooth_manager disable", btOn()) {
                        svc.openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
                    }
                },
                onLong = { svc.openSettings(Settings.ACTION_BLUETOOTH_SETTINGS) }),

            TileSpec(TileCatalog.meta("dnd")!!,
                isOn = {
                    val f = nm.currentInterruptionFilter
                    f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
                },
                onTap = {
                    if (!nm.isNotificationPolicyAccessGranted) {
                        svc.openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    } else {
                        val all = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
                        nm.setInterruptionFilter(
                            if (all) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            else NotificationManager.INTERRUPTION_FILTER_ALL
                        )
                    }
                },
                onLong = { svc.openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }),

            TileSpec(TileCatalog.meta("flashlight")!!,
                isOn = { torchOn },
                onTap = { torchId?.let { id -> runCatching { cm.setTorchMode(id, !torchOn) } } },
                onLong = {}),

            TileSpec(TileCatalog.meta("rotate")!!,
                isOn = { Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1 },
                onTap = {
                    if (!Settings.System.canWrite(svc)) {
                        svc.openSettings(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${svc.packageName}")
                    } else {
                        val cur = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
                        Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (cur == 1) 0 else 1)
                    }
                },
                onLong = { svc.openSettings(Settings.ACTION_DISPLAY_SETTINGS) }),

            TileSpec(TileCatalog.meta("battery")!!,
                isOn = { (svc.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode },
                onTap = {
                    val on = (svc.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
                    toggleVia("settings put global low_power 1", "settings put global low_power 0", on) {
                        svc.openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    }
                },
                onLong = { svc.openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS) }),

            TileSpec(TileCatalog.meta("dark")!!,
                isOn = { Palette.isSystemDark(svc) },
                onTap = {
                    toggleVia("cmd uimode night yes", "cmd uimode night no", Palette.isSystemDark(svc)) {
                        svc.openSettings(Settings.ACTION_DISPLAY_SETTINGS)
                    }
                },
                onLong = { svc.openSettings(Settings.ACTION_DISPLAY_SETTINGS) }),

            TileSpec(TileCatalog.meta("airplane")!!,
                isOn = { globalInt(Settings.Global.AIRPLANE_MODE_ON) == 1 },
                onTap = {
                    toggleVia("cmd connectivity airplane-mode enable", "cmd connectivity airplane-mode disable",
                        globalInt(Settings.Global.AIRPLANE_MODE_ON) == 1) {
                        svc.openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                    }
                },
                onLong = { svc.openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS) }),

            TileSpec(TileCatalog.meta("location")!!,
                isOn = { svc.getSystemService(LocationManager::class.java).isLocationEnabled },
                onTap = {
                    toggleVia("cmd location set-location-enabled true", "cmd location set-location-enabled false",
                        svc.getSystemService(LocationManager::class.java).isLocationEnabled) {
                        svc.openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    }
                },
                onLong = { svc.openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }),

            TileSpec(TileCatalog.meta("data")!!,
                isOn = { globalInt("mobile_data") == 1 },
                onTap = {
                    toggleVia("svc data enable", "svc data disable", globalInt("mobile_data") == 1) {
                        svc.openSettings(Settings.ACTION_NETWORK_OPERATOR_SETTINGS)
                    }
                },
                onLong = { svc.openSettings(Settings.ACTION_NETWORK_OPERATOR_SETTINGS) }),

            // ---- Privasi: sekat akses camera / mikrofon untuk semua app (Android 12+, perlu Shizuku) ----
            TileSpec(TileCatalog.meta("camera_off")!!,
                isOn = { Prefs(svc).camBlocked },
                onTap = { toggleSensor("camera") },
                onLong = { svc.openSettings(Settings.ACTION_PRIVACY_SETTINGS) },
                subtitle = { if (it) "Blocked" else "Allowed" }),

            TileSpec(TileCatalog.meta("mic_off")!!,
                isOn = { Prefs(svc).micBlocked },
                onTap = { toggleSensor("microphone") },
                onLong = { svc.openSettings(Settings.ACTION_PRIVACY_SETTINGS) },
                subtitle = { if (it) "Blocked" else "Allowed" }),

            TileSpec(TileCatalog.meta("nfc")!!,
                isOn = { nfcOn() },
                onTap = {
                    toggleVia("svc nfc enable", "svc nfc disable", nfcOn()) { svc.openSettings(Settings.ACTION_NFC_SETTINGS) }
                },
                onLong = { svc.openSettings(Settings.ACTION_NFC_SETTINGS) }),

            TileSpec(TileCatalog.meta("sound")!!,
                isOn = { audio.ringerMode != AudioManager.RINGER_MODE_NORMAL },
                onTap = {
                    // Normal -> Getar -> Senyap -> Normal. Android requires DND access to change this.
                    if (!nm.isNotificationPolicyAccessGranted) {
                        svc.openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    } else {
                        val next = when (audio.ringerMode) {
                            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                            else -> AudioManager.RINGER_MODE_NORMAL
                        }
                        runCatching { audio.ringerMode = next }
                    }
                },
                onLong = { svc.openSettings(Settings.ACTION_SOUND_SETTINGS) },
                subtitle = {
                    when (audio.ringerMode) {
                        AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                        AudioManager.RINGER_MODE_SILENT -> "Silent"
                        else -> "Sound"
                    }
                }),

            TileSpec(TileCatalog.meta("invert")!!,
                isOn = { Settings.Secure.getInt(cr, "accessibility_display_inversion_enabled", 0) == 1 },
                onTap = {
                    val on = Settings.Secure.getInt(cr, "accessibility_display_inversion_enabled", 0) == 1
                    toggleVia("settings put secure accessibility_display_inversion_enabled 1",
                        "settings put secure accessibility_display_inversion_enabled 0", on) {
                        svc.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    }
                },
                onLong = { svc.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }),

            TileSpec(TileCatalog.meta("screenshot")!!,
                isOn = { false },
                onTap = {
                    // Tutup panel dulu supaya panel tak ikut masuk dalam tangkapan skrin.
                    svc.closePanel(false)
                    if (Build.VERSION.SDK_INT >= 28) {
                        Handler(Looper.getMainLooper()).postDelayed(
                            { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 450)
                    }
                },
                onLong = {},
                subtitle = { "Tap" }),

            TileSpec(TileCatalog.meta("lock")!!,
                isOn = { false },
                onTap = {
                    svc.closePanel(false)
                    if (Build.VERSION.SDK_INT >= 28) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                },
                onLong = {},
                subtitle = { "Tap" }),

            TileSpec(TileCatalog.meta("power")!!,
                isOn = { false },
                onTap = {
                    svc.closePanel(false)
                    Handler(Looper.getMainLooper()).postDelayed(
                        { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) }, 250)
                },
                onLong = {},
                subtitle = { "Tap" }),
        )
    }

    private val audio get() = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun nfcOn() = runCatching { NfcAdapter.getDefaultAdapter(svc)?.isEnabled == true }.getOrDefault(false)

    /** Sekat/benarkan camera atau mikrofon seluruh sistem. Perlu Shizuku + Android 12+. */
    private fun toggleSensor(sensor: String) {
        val prefs = Prefs(svc)
        if (!ShizukuHelper.ready() || Build.VERSION.SDK_INT < 31) {
            svc.openSettings(Settings.ACTION_PRIVACY_SETTINGS)
            return
        }
        val blocked = if (sensor == "camera") prefs.camBlocked else prefs.micBlocked
        val verb = if (blocked) "disable" else "enable"   // "enable" = hidupkan sekatan privasi
        ShizukuHelper.run("cmd sensor_privacy $verb \$(am get-current-user) $sensor")
        if (sensor == "camera") prefs.camBlocked = !blocked else prefs.micBlocked = !blocked
    }

    /**
     * Jalankan perubahan daripada helaian HyperOS sendiri. Tidak membuka skrin Settings Android.
     * false bermaksud Android memerlukan akses istimewa/kebenaran yang belum tersedia.
     */
    fun toggleForDetails(spec: TileSpec): Boolean {
        val id = spec.meta.id
        return when (id) {
            "internet" -> {
                if (!ShizukuHelper.ready()) false else {
                    ShizukuHelper.run(if (wifi.isWifiEnabled) "svc wifi disable" else "svc wifi enable")
                    true
                }
            }
            "bluetooth" -> {
                if (!ShizukuHelper.ready()) false else {
                    ShizukuHelper.run(if (btOn()) "svc bluetooth disable || cmd bluetooth_manager disable" else "svc bluetooth enable || cmd bluetooth_manager enable")
                    true
                }
            }
            "dnd" -> {
                if (!nm.isNotificationPolicyAccessGranted) false else {
                    val all = nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
                    nm.setInterruptionFilter(if (all) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                    true
                }
            }
            "flashlight" -> {
                val idTorch = torchId ?: return false
                runCatching { cm.setTorchMode(idTorch, !torchOn) }.isSuccess
            }
            "rotate" -> {
                if (!Settings.System.canWrite(svc)) false else {
                    val cur = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
                    Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (cur == 1) 0 else 1)
                    true
                }
            }
            "battery" -> {
                if (!ShizukuHelper.ready()) false else {
                    val on = (svc.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
                    ShizukuHelper.run(if (on) "settings put global low_power 0" else "settings put global low_power 1")
                    true
                }
            }
            "dark" -> {
                if (!ShizukuHelper.ready()) false else {
                    ShizukuHelper.run(if (Palette.isSystemDark(svc)) "cmd uimode night no" else "cmd uimode night yes")
                    true
                }
            }
            "airplane" -> {
                if (!ShizukuHelper.ready()) false else {
                    ShizukuHelper.run(if (globalInt(Settings.Global.AIRPLANE_MODE_ON) == 1) "cmd connectivity airplane-mode disable" else "cmd connectivity airplane-mode enable")
                    true
                }
            }
            "location" -> {
                if (!ShizukuHelper.ready()) false else {
                    val enabled = svc.getSystemService(LocationManager::class.java).isLocationEnabled
                    ShizukuHelper.run("cmd location set-location-enabled ${!enabled}")
                    true
                }
            }
            "data" -> {
                if (!ShizukuHelper.ready()) false else {
                    ShizukuHelper.run(if (globalInt("mobile_data") == 1) "svc data disable" else "svc data enable")
                    true
                }
            }
            "camera_off" -> toggleSensorDirect("camera")
            "mic_off" -> toggleSensorDirect("microphone")
            "nfc" -> {
                if (!ShizukuHelper.ready()) false else {
                    ShizukuHelper.run(if (nfcOn()) "svc nfc disable" else "svc nfc enable")
                    true
                }
            }
            "sound" -> {
                if (!nm.isNotificationPolicyAccessGranted) false else {
                    val next = when (audio.ringerMode) {
                        AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                        AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                        else -> AudioManager.RINGER_MODE_NORMAL
                    }
                    runCatching { audio.ringerMode = next }.isSuccess
                }
            }
            "invert" -> {
                if (!ShizukuHelper.ready()) false else {
                    val on = Settings.Secure.getInt(cr, "accessibility_display_inversion_enabled", 0) == 1
                    ShizukuHelper.run("settings put secure accessibility_display_inversion_enabled ${if (on) 0 else 1}")
                    true
                }
            }
            "screenshot", "lock", "power" -> { spec.onTap(); true }
            else -> false
        }
    }

    fun detailAccessHint(id: String): String = when (id) {
        "internet" -> "The custom Wi-Fi screen is active. Android 10+ prevents ordinary apps from switching Wi-Fi directly; connect Shizuku for direct toggle control."
        "data" -> "Mobile data control is restricted by Android/carrier permissions. Connect Shizuku to toggle it here."
        "bluetooth", "battery", "dark", "airplane", "location", "nfc", "invert" -> "This setting needs Shizuku shell access. Open HyperCC Setup and connect Shizuku, then try again."
        "rotate" -> "Allow HyperCC to modify system settings, then try again."
        "dnd", "sound" -> "Allow Do Not Disturb access for HyperCC, then try again."
        "camera_off", "mic_off" -> "This privacy control needs Android 12+ and a connected Shizuku service."
        else -> "Android has not granted permission for this action."
    }

    private fun toggleSensorDirect(sensor: String): Boolean {
        if (!ShizukuHelper.ready() || Build.VERSION.SDK_INT < 31) return false
        val prefs = Prefs(svc)
        val blocked = if (sensor == "camera") prefs.camBlocked else prefs.micBlocked
        ShizukuHelper.run("cmd sensor_privacy ${if (blocked) "disable" else "enable"} \$(am get-current-user) $sensor")
        if (sensor == "camera") prefs.camBlocked = !blocked else prefs.micBlocked = !blocked
        return true
    }

    /** Tile yang dihidupkan pengguna, mengikut susunan pilihan. */
    fun allSpecs(): List<TileSpec> = specs

    fun enabled(prefs: Prefs): List<TileSpec> =
        prefs.tileOrder().filter { prefs.tileEnabled(it) }.mapNotNull { id -> specs.firstOrNull { it.meta.id == id } }
}
