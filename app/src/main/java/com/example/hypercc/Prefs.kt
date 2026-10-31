package com.example.hypercc

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Semua tetapan HyperCC di satu tempat (SharedPreferences "hypercc"). */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("hypercc", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply()
        }
    }

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    }

    /** Suis utama. false = HyperCC senyap sepenuhnya: tiada pemicu leret, tiada panel / notifikasi custom,
     *  shade sistem dipulihkan seperti biasa. */
    var enabled by bool("enabled", true)

    /** 0 = ikut sistem, 1 = cerah, 2 = gelap */
    var theme by int("theme", 0)

    /** 0 = AOSP (default), 1 = HyperOS control center layout. */
    var panelStyle by int("panel_style", 0)

    /** 0 = warna dinamik (wallpaper / Material You), selain itu warna ARGB tetap */
    var accent by int("accent", 0)

    /** Kekuatan blur 0..100 */
    var blur by int("blur", 70)

    /** Ketelusan panel 50..100 (100 = pekat) */
    var opacity by int("opacity", 82)

    /** 0 = dinamik (gaya Android 16), 1 = pil, 2 = segi empat bulat */
    var tileShape by int("tile_shape", 0)

    /** 2 = tile besar (2 lajur), 4 = tile kecil (4 lajur) */
    var tileCols by int("tile_cols", 2)

    /** false = dua panel (kiri notifikasi, kanan Quick Settings), true = satu panel gabungan */
    var singlePanel by bool("single_panel", false)

    /** Tukar sisi kiri/kanan */
    var swapSides by bool("swap_sides", false)

    /** Tinggi kawasan leret: 0 = kecil (tinggi status bar), 1 = biasa (48dp, lalai), 2 = besar (72dp), 3 = sangat besar (96dp).
     *  Kunci baru "trigger_level" supaya nilai lama (yang lalai 'besar') tidak terbawa. */
    var triggerSize by int("trigger_level", 1)

    var haptics by bool("haptics", true)

    /** Tutup shade notifikasi/QS sistem jika ia terbuka, supaya hanya panel HyperCC kelihatan (Android 12+). */
    var takeover by bool("takeover", true)

    /** Matikan terus tarikan shade sistem (notifikasi + QS). Perlu Shizuku. */
    var disableShade by bool("disable_system_shade", false)

    /** Sembunyikan Quick Settings sistem (perlu Shizuku). Serasi dengan kunci lama. */
    var hideStockQs: Boolean
        get() = sp.getBoolean("disable_stock_qs", false) || sp.getBoolean("disable_stock_shade", false)
        set(v) { sp.edit().putBoolean("disable_stock_qs", v).putBoolean("disable_stock_shade", false).apply() }

    /** Keadaan tile Camera off / Mic off (Android tak bagi baca semula status ini, jadi kita ingat sendiri). */
    var camBlocked by bool("cam_blocked", false)
    var micBlocked by bool("mic_blocked", false)

    /** Susunan dan keterlihatan kawalan kecerahan/bunyi dalam Quick Settings. */
    fun controlOrder(): List<String> {
        val all = listOf("brightness", "sound")
        val saved = sp.getString("control_order", null)?.split(",")?.filter { it in all } ?: emptyList()
        return saved + all.filter { it !in saved }
    }
    fun setControlOrder(ids: List<String>) { sp.edit().putString("control_order", ids.joinToString(",")).apply() }
    fun controlEnabled(id: String): Boolean = sp.getBoolean("control_on_$id", true)
    fun setControlEnabled(id: String, on: Boolean) { sp.edit().putBoolean("control_on_$id", on).apply() }

    fun tileOrder(): List<String> {
        val all = TileCatalog.all.map { it.id }
        val saved = sp.getString("tile_order", null)?.split(",")?.filter { it in all } ?: emptyList()
        return saved + all.filter { it !in saved }
    }

    fun setTileOrder(ids: List<String>) { sp.edit().putString("tile_order", ids.joinToString(",")).apply() }

    fun tileEnabled(id: String): Boolean =
        sp.getBoolean("tile_on_$id", TileCatalog.all.firstOrNull { it.id == id }?.defaultOn ?: false)

    fun setTileEnabled(id: String, on: Boolean) { sp.edit().putBoolean("tile_on_$id", on).apply() }

    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)
    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.unregisterOnSharedPreferenceChangeListener(l)
}
