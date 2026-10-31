package com.example.hypercc

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Token warna ala Material 3 / Android 16.
 * Pada Android 12+ dan accent = "Dynamic", warna diambil terus daripada palet sistem (wallpaper).
 */
class Palette(
    val dark: Boolean,
    val primary: Int, val onPrimary: Int,
    val primaryContainer: Int, val onPrimaryContainer: Int,
    val surface: Int,      // latar halaman / panel
    val container: Int,    // kad
    val high: Int,         // tile tak aktif, chip
    val onSurface: Int, val onVariant: Int, val outline: Int,
    val secondary: Int, val onSecondary: Int // secondaryContainer
) {
    companion object {
        fun isSystemDark(ctx: Context): Boolean =
            (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        fun build(ctx: Context, prefs: Prefs): Palette {
            val dark = when (prefs.theme) { 1 -> false; 2 -> true; else -> isSystemDark(ctx) }
            // HyperOS uses a neutral charcoal/white surface system and Xiaomi blue,
            // rather than applying a blue hue to every surface as the old mock did.
            if (prefs.panelStyle == 1) {
                return hyperOs(dark, if (prefs.accent == 0) 0xFF3487F8.toInt() else prefs.accent)
            }
            return if (prefs.accent == 0 && Build.VERSION.SDK_INT >= 31) dynamic(ctx, dark)
            else fromSeed(if (prefs.accent == 0) DEFAULT_SEED else prefs.accent, dark)
        }

        const val DEFAULT_SEED = 0xFF4285F4.toInt()

        private fun hyperOs(dark: Boolean, seed: Int): Palette {
            val blue = seed
            return if (dark) Palette(
                true,
                blue, Color.WHITE,
                mix(blue, Color.BLACK, .58f), 0xFFD7E7FF.toInt(),
                0xFF111318.toInt(), 0xFF262A31.toInt(), 0xFF343942.toInt(),
                0xFFF5F6F8.toInt(), 0xFFADB4BF.toInt(), 0xFF5B6470.toInt(),
                0xFF303B4B.toInt(), 0xFFE0E9F8.toInt()
            ) else Palette(
                false,
                blue, Color.WHITE,
                mix(blue, Color.WHITE, .84f), 0xFF17365F.toInt(),
                0xFFF1F3F7.toInt(), Color.WHITE, 0xFFE2E6EC.toInt(),
                0xFF171A20.toInt(), 0xFF626B78.toInt(), 0xFF929BA7.toInt(),
                0xFFE8EDF5.toInt(), 0xFF253B59.toInt()
            )
        }

        @SuppressLint("NewApi")
        private fun dynamic(ctx: Context, dark: Boolean): Palette {
            fun c(id: Int) = ctx.getColor(id)
            return if (dark) Palette(
                true,
                c(android.R.color.system_accent1_200), c(android.R.color.system_accent1_800),
                c(android.R.color.system_accent1_700), c(android.R.color.system_accent1_100),
                c(android.R.color.system_neutral1_900), c(android.R.color.system_neutral1_800), c(android.R.color.system_neutral1_700),
                c(android.R.color.system_neutral1_100), c(android.R.color.system_neutral2_200), c(android.R.color.system_neutral2_400),
                c(android.R.color.system_accent2_700), c(android.R.color.system_accent2_100)
            ) else Palette(
                false,
                c(android.R.color.system_accent1_600), c(android.R.color.system_accent1_0),
                c(android.R.color.system_accent1_100), c(android.R.color.system_accent1_900),
                c(android.R.color.system_neutral1_50), c(android.R.color.system_neutral1_10), c(android.R.color.system_neutral1_100),
                c(android.R.color.system_neutral1_900), c(android.R.color.system_neutral2_700), c(android.R.color.system_neutral2_500),
                c(android.R.color.system_accent2_100), c(android.R.color.system_accent2_900)
            )
        }

        private fun fromSeed(seed: Int, dark: Boolean): Palette {
            val hsv = FloatArray(3)
            Color.colorToHSV(seed, hsv)
            val h = hsv[0]
            return if (dark) Palette(
                true,
                hsl(h, .78f, .78f), hsl(h, .70f, .17f),
                hsl(h, .45f, .27f), hsl(h, .80f, .90f),
                hsl(h, .10f, .09f), hsl(h, .09f, .15f), hsl(h, .09f, .22f),
                hsl(h, .10f, .91f), hsl(h, .10f, .76f), hsl(h, .08f, .55f),
                hsl(h, .18f, .26f), hsl(h, .30f, .90f)
            ) else Palette(
                false,
                hsl(h, .62f, .38f), Color.WHITE,
                hsl(h, .80f, .90f), hsl(h, .85f, .14f),
                hsl(h, .14f, .95f), hsl(h, .20f, .995f), hsl(h, .16f, .90f),
                hsl(h, .12f, .10f), hsl(h, .08f, .32f), hsl(h, .06f, .48f),
                hsl(h, .30f, .88f), hsl(h, .45f, .14f)
            )
        }

        fun hsl(h: Float, s: Float, l: Float): Int {
            val c = (1f - abs(2f * l - 1f)) * s
            val hp = ((h % 360f) + 360f) % 360f / 60f
            val x = c * (1f - abs(hp % 2f - 1f))
            val rgb = when {
                hp < 1 -> floatArrayOf(c, x, 0f)
                hp < 2 -> floatArrayOf(x, c, 0f)
                hp < 3 -> floatArrayOf(0f, c, x)
                hp < 4 -> floatArrayOf(0f, x, c)
                hp < 5 -> floatArrayOf(x, 0f, c)
                else -> floatArrayOf(c, 0f, x)
            }
            val m = l - c / 2f
            fun ch(v: Float) = ((v + m) * 255f).roundToInt().coerceIn(0, 255)
            return Color.rgb(ch(rgb[0]), ch(rgb[1]), ch(rgb[2]))
        }

        /** Campur dua warna. t = 0 -> a, t = 1 -> b */
        fun mix(a: Int, b: Int, t: Float): Int {
            fun m(x: Int, y: Int) = (x + (y - x) * t).roundToInt().coerceIn(0, 255)
            return Color.argb(m(Color.alpha(a), Color.alpha(b)), m(Color.red(a), Color.red(b)), m(Color.green(a), Color.green(b)), m(Color.blue(a), Color.blue(b)))
        }
    }
}
