package io.github.takosasidev.shelftally.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import io.github.takosasidev.shelftally.core.calc.StockLevel
import io.github.takosasidev.shelftally.core.model.AppSettings
import io.github.takosasidev.shelftally.core.model.DarkModeMode
import io.github.takosasidev.shelftally.core.model.ThemeColorMode

/** 残量バーの「少ない(黄)」。十分は primary、在庫切れは error を使うので、足りない 1 色だけを持つ。 */
data class StockColors(val low: Color)

val LocalStockColors = staticCompositionLocalOf { StockColors(low = Color(0xFF8A6100)) }

@Composable
fun stockColor(level: StockLevel): Color = when (level) {
    StockLevel.OK -> MaterialTheme.colorScheme.primary
    StockLevel.LOW -> LocalStockColors.current.low
    StockLevel.OUT -> MaterialTheme.colorScheme.error
}

/** FR-21 の手動の色の候補。カテゴリの色の候補にも使う。Material You の無い端末(Android 11 以下)の代わりも兼ねる。 */
val ThemeColorChoices: List<Pair<String, Color>> = listOf(
    "インディゴ" to Color(0xFF3F51B5),
    "ティール" to Color(0xFF00796B),
    "パープル" to Color(0xFF6750A4),
    "オレンジ" to Color(0xFFE07A2F),
    "グリーン" to Color(0xFF2E7D32),
    "ローズ" to Color(0xFFB3325B),
    "ブラウン" to Color(0xFF6D4C41),
    "グレー" to Color(0xFF546E7A),
)

/** 色の上に載せる文字の色。明るい色には黒、暗い色には白(カテゴリの色を自由に選んでも読めるように)。 */
fun onColor(background: Color): Color = if (background.luminance() > 0.4f) Color.Black else Color.White

private val FallbackLight = lightColorScheme()
private val FallbackDark = darkColorScheme()

@Composable
fun ShelfTallyTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // FR-22: システムに合わせる / 常にライト / 常にダーク
    val dark = when (settings.darkModeMode) {
        DarkModeMode.SYSTEM -> isSystemInDarkTheme()
        DarkModeMode.LIGHT -> false
        DarkModeMode.DARK -> true
    }
    val manual = settings.manualThemeColor
    // 壁紙からの配色は Android 12 以上だけ。それより古い端末は決まった配色にする
    val scheme = if (settings.themeColorMode == ThemeColorMode.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (manual != null) {
        schemeFromSeed(Color(manual), dark)
    } else {
        if (dark) FallbackDark else FallbackLight
    }
    CompositionLocalProvider(
        LocalStockColors provides StockColors(low = if (dark) Color(0xFFF5C518) else Color(0xFF8A6100)),
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * 種の色からの配色。primary の系統だけを差し替え、残りは標準の配色を使う。
 * shortcut: 色の調和までは作らない。そろえたくなったら material-color-utilities を足す
 */
private fun schemeFromSeed(seed: Color, dark: Boolean) = if (dark) {
    FallbackDark.copy(
        primary = seed.lighten(0.35f),
        onPrimary = Color.Black,
        primaryContainer = seed.darken(0.35f),
        onPrimaryContainer = seed.lighten(0.6f),
        secondary = seed.lighten(0.2f),
    )
} else {
    FallbackLight.copy(
        primary = seed,
        onPrimary = Color.White,
        primaryContainer = seed.lighten(0.7f),
        onPrimaryContainer = seed.darken(0.5f),
        secondary = seed.darken(0.15f),
    )
}

private fun Color.lighten(f: Float) = Color(red + (1f - red) * f, green + (1f - green) * f, blue + (1f - blue) * f, alpha)

private fun Color.darken(f: Float) = Color(red * (1f - f), green * (1f - f), blue * (1f - f), alpha)
