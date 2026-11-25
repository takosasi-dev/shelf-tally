@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.takosasidev.shelftally.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.takosasidev.shelftally.core.calc.Format
import io.github.takosasidev.shelftally.core.calc.Stats
import io.github.takosasidev.shelftally.ui.ShelfViewModel
import io.github.takosasidev.shelftally.ui.components.EmptyState
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

    Scaffold(topBar = { TopAppBar(title = { Text("統計") }) }) { padding ->
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
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel("今月の支出(概算)") {
                Text(Format.yen(months.last().second), style = MaterialTheme.typography.headlineMedium)
                Text(
                    "補充のときに金額を入れた分だけの合計です",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val max = months.maxOf { it.second }.coerceAtLeast(1)
                months.forEach { (month, yen) ->
                    Bar("${month.monthValue}月", yen.toFloat() / max, Format.yen(yen), MaterialTheme.colorScheme.primary)
                }
            }

            Panel("カテゴリ別の消費(直近${CONSUMPTION_DAYS}日)") {
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

            Panel("よく買う品目") {
                if (top.isEmpty()) {
                    Text("補充の記録はまだありません", style = MaterialTheme.typography.bodySmall)
                } else {
                    top.forEachIndexed { index, row ->
                        Text(
                            "${index + 1}. ${row.item.name}  ${row.count}回" + if (row.totalPrice > 0) "(計 ${Format.yen(row.totalPrice)})" else "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** 名前・横棒・数。長さだけでなく数も書く。 */
@Composable
private fun Bar(label: String, fraction: Float, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(80.dp))
        Box(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(12.dp).clip(RoundedCornerShape(6.dp)).background(color))
        }
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
    }
}
