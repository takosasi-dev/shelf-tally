package io.github.takosasidev.shelftally.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import io.github.takosasidev.shelftally.core.calc.StockLevel
import io.github.takosasidev.shelftally.core.model.AppSettings
import io.github.takosasidev.shelftally.core.model.DarkModeMode
import io.github.takosasidev.shelftally.core.model.ThemeColorMode

/**
 * 残量の「十分(緑)」と「少ない(黄)」。在庫切れは error を使う。
 * 十分をテーマの色にすると、オレンジやローズのテーマで注意の色と見分けにくいので、決まった緑にする。
 */
data class StockColors(val ok: Color, val low: Color)

val LocalStockColors = staticCompositionLocalOf { StockColors(ok = Color(0xFF2E7D32), low = Color(0xFF8A6100)) }

@Composable
fun stockColor(level: StockLevel): Color = when (level) {
    StockLevel.OK -> LocalStockColors.current.ok
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
private const val DefaultSeed = 0xFF00796B.toInt()

/**
 * カテゴリの色を「淡い地」と「濃い前景(アイコン・文字)」の組にする。濃いベタ塗りより落ち着いて見え、
 * 前景は地に対して読める濃さにする(ライトは黒へ、ダークは白へ寄せる)。
 */
@Composable
fun categoryTones(color: Color): Pair<Color, Color> {
    val surface = MaterialTheme.colorScheme.surface
    return if (surface.luminance() < 0.5f) {
        lerp(surface, color, 0.3f) to lerp(color, Color.White, 0.55f)
    } else {
        lerp(surface, color, 0.14f) to lerp(color, Color.Black, 0.35f)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    } else {
        // 色を選んでいない古い端末も、標準の紫ではなくアプリの色(ティール)にする
        schemeFromSeed(Color(manual ?: DefaultSeed), dark)
    }
    CompositionLocalProvider(
        LocalStockColors provides if (dark) {
            StockColors(ok = Color(0xFF81C784), low = Color(0xFFF5C518))
        } else {
            StockColors(ok = Color(0xFF2E7D32), low = Color(0xFF8A6100))
        },
    ) {
        MaterialExpressiveTheme(colorScheme = scheme, content = content)
    }
}

/**
 * 種の色からの配色。primary の系統に加えて、背景・カード・トーンのボタンの地にも種の色を薄く混ぜる
 * (混ぜないと標準の紫がかった灰色が残り、カードが沈んで見える)。
 * shortcut: 色相を保った正式なトーンの計算はしない。そろえたくなったら material-color-utilities を足す
 */
private fun schemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val base = if (dark) Color(0xFF101414) else Color.White
    fun tint(f: Float) = lerp(base, seed, f)
    return if (dark) {
        FallbackDark.copy(
            primary = seed.lighten(0.35f),
            onPrimary = Color.Black,
            primaryContainer = seed.darken(0.35f),
            onPrimaryContainer = seed.lighten(0.6f),
            secondary = seed.lighten(0.2f),
            secondaryContainer = tint(0.32f),
            onSecondaryContainer = seed.lighten(0.75f),
            onSurface = Color(0xFFE0E3E3),
            onBackground = Color(0xFFE0E3E3),
            onSurfaceVariant = lerp(Color(0xFFC0C6C6), seed, 0.15f),
            outline = lerp(Color(0xFF899292), seed, 0.2f),
            outlineVariant = tint(0.28f),
            background = tint(0.03f),
            surface = tint(0.03f),
            surfaceVariant = tint(0.16f),
            surfaceTint = seed.lighten(0.35f),
            surfaceContainerLowest = base,
            surfaceContainerLow = tint(0.06f),
            surfaceContainer = tint(0.09f),
            surfaceContainerHigh = tint(0.12f),
            surfaceContainerHighest = tint(0.16f),
        )
    } else {
        FallbackLight.copy(
            primary = seed,
            onPrimary = Color.White,
            primaryContainer = seed.lighten(0.7f),
            onPrimaryContainer = seed.darken(0.5f),
            secondary = seed.darken(0.15f),
            secondaryContainer = tint(0.2f),
            onSecondaryContainer = seed.darken(0.6f),
            onSurface = Color(0xFF181C1C),
            onBackground = Color(0xFF181C1C),
            onSurfaceVariant = lerp(Color(0xFF424848), seed, 0.2f),
            outline = lerp(Color(0xFF727878), seed, 0.2f),
            outlineVariant = tint(0.22f),
            background = tint(0.02f),
            surface = tint(0.02f),
            surfaceVariant = tint(0.1f),
            surfaceTint = seed,
            surfaceContainerLowest = base,
            surfaceContainerLow = tint(0.04f),
            surfaceContainer = tint(0.06f),
            surfaceContainerHigh = tint(0.08f),
            surfaceContainerHighest = tint(0.1f),
        )
    }
}

private fun Color.lighten(f: Float) = Color(red + (1f - red) * f, green + (1f - green) * f, blue + (1f - blue) * f, alpha)

private fun Color.darken(f: Float) = Color(red * (1f - f), green * (1f - f), blue * (1f - f), alpha)
