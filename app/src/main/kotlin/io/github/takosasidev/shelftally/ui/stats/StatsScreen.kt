@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.takosasidev.shelftally.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.takosasidev.shelftally.core.calc.Format
import io.github.takosasidev.shelftally.core.calc.Stats
import io.github.takosasidev.shelftally.ui.ShelfViewModel
import io.github.takosasidev.shelftally.ui.components.EmptyState
import io.github.takosasidev.shelftally.ui.components.groupShape
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** カテゴリ別の消費を数える期間(日)。 */
private const val CONSUMPTION_DAYS = 30L

/**
 * FR-26: 消費の統計。カテゴリ別の消費の回数、月ごとの支出の概算、よく買う品目。
 * 支出は、補充のときに金額を入れた記録だけを足した概算(金額の無い補充は 0 円として数えない)。
 */
@Composable
fun StatsScreen(vm: ShelfViewModel) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text("統計") }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        if (logs.isEmpty()) {
            EmptyState(
                Icons.Filled.BarChart,
                "まだ記録がありません",
                "在庫を増やしたり減らしたりすると、ここに消費の傾向と支出の概算が出ます。",
                Modifier.padding(padding),
            )
        } else {
            StatsBody(vm, Modifier.padding(padding))
        }
    }
}

@Composable
private fun StatsBody(vm: ShelfViewModel, modifier: Modifier) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    run {
        val byCategory = remember(logs, items) {
            Stats.consumptionByCategory(logs, items, Instant.now().minus(CONSUMPTION_DAYS, ChronoUnit.DAYS))
        }
        val months = remember(logs) { Stats.spendByMonth(logs, YearMonth.now(zone), 6, zone) }
        val top = remember(logs, items) { Stats.topPurchased(logs, items) }
        val categoryById = remember(categories) { categories.associateBy { it.id } }

        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Panel("今月の支出(概算)", groupShape(0, 3)) {
                Text(Format.yen(months.last().second), style = MaterialTheme.typography.displaySmallEmphasized)
                Text(
                    "補充のときに金額を入れた分だけの合計です",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MonthChart(months)
            }

            Panel("カテゴリ別の消費(直近${CONSUMPTION_DAYS}日)", groupShape(1, 3)) {
                if (byCategory.isEmpty()) {
                    Text("この期間の消費の記録はありません", style = MaterialTheme.typography.bodySmall)
                } else {
                    val max = byCategory.maxOf { it.count }.coerceAtLeast(1)
                    byCategory.forEach { row ->
                        val category = categoryById[row.categoryId]
                        Bar(
                            category?.name ?: "未分類",
                            row.count.toFloat() / max,
                            "${row.count}回",
                            category?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            Panel("よく買う品目", groupShape(2, 3)) {
                if (top.isEmpty()) {
                    Text("補充の記録はまだありません", style = MaterialTheme.typography.bodySmall)
                } else {
                    top.forEachIndexed { index, row ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            // 順位の丸。1 位だけ色を濃くする
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(
                                    if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (index == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Text(
                                row.item.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${row.count}回", style = MaterialTheme.typography.labelLarge)
                                if (row.totalPrice > 0) {
                                    Text(
                                        "計 ${Format.yen(row.totalPrice)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(title: String, shape: Shape, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = shape) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/**
 * 月ごとの支出の縦棒。どの月にも空の枠を描き、0 円の月も「無い」と分かるようにする。
 * 今月だけ濃い色と金額を出し、ほかの月は金額を棒の下に小さく書く(長さだけに頼らない)。
 */
@Composable
private fun MonthChart(months: List<Pair<YearMonth, Int>>) {
    val max = months.maxOf { it.second }.coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        months.forEachIndexed { i, (month, yen) ->
            val current = i == months.lastIndex
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (yen > 0) {
                        Box(
                            Modifier.fillMaxWidth().fillMaxHeight(yen.toFloat() / max).clip(RoundedCornerShape(10.dp))
                                .background(if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                        )
                    }
                }
                Text(
                    "${month.monthValue}月",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    if (yen > 0) Format.yen(yen) else "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 名前・横棒・数。長さだけでなく数も書く。棒の後ろに枠を敷いて、全体の長さを見せる。 */
@Composable
private fun Bar(label: String, fraction: Float, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(80.dp))
        Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(color))
        }
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
    }
}
