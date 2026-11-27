@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.takosasidev.shelftally.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Shape
import io.github.takosasidev.shelftally.ui.components.groupShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.takosasidev.shelftally.core.calc.Expiry
import io.github.takosasidev.shelftally.core.calc.ExpiryStatus
import io.github.takosasidev.shelftally.core.calc.Forecast
import io.github.takosasidev.shelftally.core.calc.Format
import io.github.takosasidev.shelftally.core.calc.Recurring
import io.github.takosasidev.shelftally.core.calc.Stats
import io.github.takosasidev.shelftally.core.calc.Stock
import io.github.takosasidev.shelftally.core.model.Category
import io.github.takosasidev.shelftally.core.model.ChangeType
import io.github.takosasidev.shelftally.core.model.ConsumptionLog
import io.github.takosasidev.shelftally.core.model.Item
import io.github.takosasidev.shelftally.ui.ShelfViewModel
import io.github.takosasidev.shelftally.ui.components.AmountDialog
import io.github.takosasidev.shelftally.ui.components.ItemThumb
import io.github.takosasidev.shelftally.ui.components.StepButton
import io.github.takosasidev.shelftally.ui.components.StockBar
import io.github.takosasidev.shelftally.ui.components.stockText
import io.github.takosasidev.shelftally.ui.theme.LocalStockColors
import io.github.takosasidev.shelftally.ui.theme.stockColor
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val DateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd")

/**
 * 品目の詳細。数量の入力(FR-02)、予測(FR-11)、期限(FR-09)、価格の推移と購入の頻度(FR-10)。
 * バーコードで既にある品目を読んだときも、ここが開く(FR-06 の「数量入力画面」)。
 */
@Composable
fun ItemDetailScreen(vm: ShelfViewModel, itemId: Long, onBack: () -> Unit, onEdit: () -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val logsByItem by vm.logsByItem.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val item = items.firstOrNull { it.id == itemId }
    val logs = logsByItem[itemId].orEmpty()

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var amountFor by remember { mutableStateOf<ChangeType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
                actions = {
                    if (item != null) {
                        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "編集") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "その他") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("買い物リストに追加") },
                                    onClick = {
                                        menu = false
                                        scope.launch {
                                            val added = vm.repo.addItemToShopping(item)
                                            snackbar.showSnackbar(if (added) "買い物リストに追加しました" else "買い物リストに既にあります")
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (item.isArchived) "アーカイブから戻す" else "アーカイブする") },
                                    onClick = {
                                        menu = false
                                        vm.act { setArchived(item.id, !item.isArchived) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("削除") },
                                    onClick = {
                                        menu = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (item == null) {
            // 読み込みの途中か、消した直後。何も出さない
            Box(Modifier.padding(padding).fillMaxSize())
        } else {
            DetailBody(
                item = item,
                logs = logs,
                category = categories.firstOrNull { it.id == item.categoryId },
                leadDays = settings?.expiryAlertLeadDays ?: 3,
                today = today,
                modifier = Modifier.padding(padding),
                onStep = { type ->
                    val step = Stock.defaultStep(item.unit)
                    if (step == null) amountFor = type else vm.act { changeQuantity(item.id, type, step) }
                },
                onAmount = { amountFor = it },
            )
        }
    }

    val target = amountFor
    if (target != null && item != null) {
        AmountDialog(
            title = if (target == ChangeType.CONSUME) "使った量" else "補充した量",
            unit = item.unit,
            withPrice = target == ChangeType.RESTOCK,
            onConfirm = { amount, price ->
                vm.act { changeQuantity(item.id, target, amount, price) }
                amountFor = null
            },
            onDismiss = { amountFor = null },
        )
    }

    if (confirmDelete && item != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("「${item.name}」を削除しますか") },
            // FR-27: 履歴も一緒に消えることをはっきり書く
            text = { Text("この品目の履歴(${logs.size} 件)と、買い物リストの行も一緒に消えます。元に戻せません。残しておきたいときは「アーカイブ」を使ってください。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.act { deleteItem(item.id) }
                    onBack()
                }) { Text("削除する", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DetailBody(
    item: Item,
    logs: List<ConsumptionLog>,
    category: Category?,
    leadDays: Int,
    today: LocalDate,
    modifier: Modifier,
    onStep: (ChangeType) -> Unit,
    onAmount: (ChangeType) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ItemThumb(item, category, 96.dp, reqPx = 512)
            Column(Modifier.padding(start = 16.dp)) {
                Text(
                    listOfNotNull(category?.name, item.locationTag, "アーカイブ中".takeIf { item.isArchived }).joinToString(" ・ "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Format.quantity(item.currentQuantity, item.unit),
                    style = MaterialTheme.typography.displaySmallEmphasized,
                    color = stockColor(Stock.level(item)),
                )
                Text(stockText(item, logs), style = MaterialTheme.typography.bodyMedium)
            }
        }
        StockBar(item, logs)
        Text(
            "${Format.quantity(item.lowStockThreshold, item.unit)} 以下になるとお知らせします。" +
                "満タンは、これまでで一番多かった残量です(それがお知らせの量以下なら、お知らせの量の 3 倍)。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StepButton(Icons.Filled.Remove, "減らす", enabled = item.currentQuantity > 0.0) { onStep(ChangeType.CONSUME) }
            StepButton(Icons.Filled.Add, "増やす") { onStep(ChangeType.RESTOCK) }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(onClick = { onAmount(ChangeType.CONSUME) }, enabled = item.currentQuantity > 0.0, modifier = Modifier.weight(1f)) {
                Text("使った量")
            }
            Button(onClick = { onAmount(ChangeType.RESTOCK) }, modifier = Modifier.weight(1f)) { Text("補充") }
        }

        // 欄は 1 つのまとまりとして並べる(出す欄の数で端の角が決まる)
        val sections = buildList<Pair<String, @Composable () -> Unit>> {
            add("残量の予測" to { Text(Forecast.label(Forecast.remainingDays(item.currentQuantity, logs, zone), item.currentQuantity)) })
            item.expiryDate?.let { date ->
                val status = Expiry.status(date, today, leadDays)
                add("期限" to {
                    Text(
                        "${DateFmt.format(date)}(${Expiry.label(date, today)})",
                        color = when (status) {
                            ExpiryStatus.EXPIRED -> MaterialTheme.colorScheme.error
                            ExpiryStatus.SOON -> LocalStockColors.current.low
                            else -> Color.Unspecified
                        },
                    )
                })
            }
            Recurring.nextDue(item)?.let { due ->
                add("定期購入" to { Text("${item.recurringPurchaseIntervalDays}日ごと。次のお知らせは ${DateFmt.format(due.atZone(zone))} ごろ") })
            }
            item.barcodeValue?.let { add("バーコード" to { Text(it) }) }
            add("購入の記録" to { PurchaseHistory(item, logs, zone) })
            if (logs.isNotEmpty()) add("最近の記録" to { RecentLogs(item, logs, zone) })
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            sections.forEachIndexed { i, (title, body) -> Section(title, groupShape(i, sections.size), body) }
        }
    }
}

@Composable
private fun PurchaseHistory(item: Item, logs: List<ConsumptionLog>, zone: ZoneId) {
    val interval = Stats.averagePurchaseIntervalDays(logs)
    Text(if (interval != null) "約${interval.roundToInt().coerceAtLeast(1)}日ごとに購入" else "購入の頻度は、補充を 2 回記録すると出ます")
    val prices = Stats.priceHistory(logs, zone).takeLast(8)
    if (prices.isEmpty()) {
        Text(
            "補充のときに金額を入れると、価格の推移が出ます",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val max = prices.maxOf { it.price }.coerceAtLeast(1)
        prices.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(DateFmt.format(p.date), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(88.dp))
                // 棒の後ろに枠を敷いて、いちばん高い値までの長さを見せる
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
                    Box(
                        Modifier.fillMaxWidth(p.price.toFloat() / max).fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    "${Format.yen(p.price)} / ${Format.quantity(p.amount, item.unit)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RecentLogs(item: Item, logs: List<ConsumptionLog>, zone: ZoneId) {
    logs.take(10).forEach { log ->
        val sign = if (log.changeType == ChangeType.CONSUME) "−" else "＋"
        val price = log.price?.let { "(${Format.yen(it)})" }.orEmpty()
        Text(
            "${DateFmt.format(log.recordedAt.atZone(zone))}  $sign${Format.quantity(log.changeAmount, item.unit)}$price",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Section(title: String, shape: Shape, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = shape) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmallEmphasized, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
