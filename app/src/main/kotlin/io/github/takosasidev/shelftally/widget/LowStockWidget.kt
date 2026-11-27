package io.github.takosasidev.shelftally.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.takosasidev.shelftally.MainActivity
import io.github.takosasidev.shelftally.core.calc.Format
import io.github.takosasidev.shelftally.core.calc.Stock
import io.github.takosasidev.shelftally.core.calc.StockLevel
import io.github.takosasidev.shelftally.core.model.Item
import io.github.takosasidev.shelftally.data.ShelfRepository
import kotlinx.coroutines.flow.catch

/** FR-16: ホーム画面のウィジェット。低在庫の品目を並べ、タップでアプリを開く。 */
class LowStockWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LowStockWidget
}

/** 並べる行の上限。3x2 の大きさに収まる数。残りは「ほか N 件」と書く。 */
private const val MAX_ROWS = 5

object LowStockWidget : GlanceAppWidget() {

    /** 残量が変わるたびに呼ぶ。30 分ごとの更新を待つと、低在庫になってもすぐに映らない。 */
    suspend fun refresh(context: Context) {
        runCatching { updateAll(context) }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 最初の 1 回は provideContent の前に読み、失敗も握る。
        // ここで投げるとホーム画面に「読み込めません」が出る。
        val repo = ShelfRepository.get(context)
        val first = runCatching { repo.itemsOnce() }.getOrNull()

        provideContent {
            // 描いている間(Glance のセッションが生きている数十秒)は、updateAll を呼んでも provideGlance は
            // 呼び直されない。先に読んだ一覧だけを描くと、続けて − を押した 2 回目(ちょうど低在庫に変わる回)が
            // 映らないので、DB の変化をここで見る。
            val items by remember { repo.items.catch { } }.collectAsState(first)
            Body(items?.let(::lowOf))
        }
    }

    private fun lowOf(items: List<Item>): List<Item> =
        items.filter { !it.isArchived && Stock.isLow(it) }
            .sortedWith(compareBy<Item> { Stock.level(it).ordinal }.thenBy { it.name })
}

@Composable
private fun Body(low: List<Item>?) {
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text(
                text = if (low.isNullOrEmpty()) "在庫" else "残り少ない物(${low.size})",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            )
            when {
                low == null -> Line("-", "")
                low.isEmpty() -> Line("少ない物はありません", "")
                else -> {
                    low.take(MAX_ROWS).forEach { item ->
                        Line(
                            name = item.name,
                            // 色だけに頼らず、切れている物は文字でも分かるようにする
                            amount = if (Stock.level(item) == StockLevel.OUT) "在庫切れ" else Format.quantity(item.currentQuantity, item.unit),
                            out = Stock.level(item) == StockLevel.OUT,
                        )
                    }
                    if (low.size > MAX_ROWS) Line("ほか ${low.size - MAX_ROWS} 件", "")
                }
            }
        }
    }
}

@Composable
private fun Line(name: String, amount: String, out: Boolean = false) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            maxLines = 1,
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = amount,
            maxLines = 1,
            style = TextStyle(
                color = if (out) GlanceTheme.colors.error else GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}
