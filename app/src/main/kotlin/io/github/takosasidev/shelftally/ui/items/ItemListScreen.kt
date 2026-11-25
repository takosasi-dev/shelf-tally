@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.takosasidev.shelftally.ui.items

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.takosasidev.shelftally.core.calc.Expiry
import io.github.takosasidev.shelftally.core.calc.ExpiryStatus
import io.github.takosasidev.shelftally.core.calc.Forecast
import io.github.takosasidev.shelftally.core.calc.ForecastResult
import io.github.takosasidev.shelftally.core.calc.ItemFilter
import io.github.takosasidev.shelftally.core.calc.ItemQuery
import io.github.takosasidev.shelftally.core.calc.ItemSort
import io.github.takosasidev.shelftally.core.calc.LevelFilter
import io.github.takosasidev.shelftally.core.calc.Stock
import io.github.takosasidev.shelftally.core.model.Category
import io.github.takosasidev.shelftally.core.model.ChangeType
import io.github.takosasidev.shelftally.core.model.ConsumptionLog
import io.github.takosasidev.shelftally.core.model.Item
import io.github.takosasidev.shelftally.core.model.ViewMode
import io.github.takosasidev.shelftally.ui.ShelfViewModel
import io.github.takosasidev.shelftally.ui.components.AmountDialog
import io.github.takosasidev.shelftally.ui.components.EmptyState
import io.github.takosasidev.shelftally.ui.components.ItemThumb
import io.github.takosasidev.shelftally.ui.components.StepButton
import io.github.takosasidev.shelftally.ui.components.StockBar
import io.github.takosasidev.shelftally.ui.components.stockText
import io.github.takosasidev.shelftally.ui.theme.LocalStockColors
import io.github.takosasidev.shelftally.ui.theme.onColor
import java.time.LocalDate
import java.time.ZoneId

/** 一覧の 1 行に要る物をまとめた形。行の Composable の引数を減らす。 */
private class RowData(
    val item: Item,
    val category: Category?,
    val logs: List<ConsumptionLog>,
    val expiry: ExpiryStatus,
    /** 予測が出せるときだけ入る。一覧では「データ不足」を毎行に出さない(詳細の画面で出す)。 */
    val forecast: String?,
)

/**
 * 在庫の一覧(FR-02 / FR-14 / FR-23)。リストとグリッドを切り替えられ、どちらも行の上で ＋/− ができる
 * (NFR-03: 一覧から 2 タップ以内で数量を変えられる)。
 */
@Composable
fun ItemListScreen(vm: ShelfViewModel, onOpen: (Long) -> Unit, onAdd: () -> Unit, onScan: () -> Unit) {
    val items by vm.visibleItems.collectAsStateWithLifecycle()
    val allItems by vm.items.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val logsByItem by vm.logsByItem.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val chosenMode by vm.viewMode.collectAsStateWithLifecycle()
    val mode = chosenMode ?: settings?.defaultViewMode ?: ViewMode.LIST
    val leadDays = settings?.expiryAlertLeadDays ?: 3

    var searching by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var amountFor by remember { mutableStateOf<Pair<Item, ChangeType>?>(null) }
    val today = remember { LocalDate.now() }
    val zone = remember { ZoneId.systemDefault() }
    val categoryById = remember(categories) { categories.associateBy { it.id } }
    val lowCount = allItems.count { !it.isArchived && Stock.isLow(it) }

    // 個数の単位は 1 つずつ。量り売りの単位は幅を決められないので、量を入力させる
    val onStep: (Item, ChangeType) -> Unit = { item, type ->
        val step = Stock.defaultStep(item.unit)
        if (step == null) amountFor = item to type else vm.act { changeQuantity(item.id, type, step) }
    }

    val rows = remember(items, categoryById, logsByItem, leadDays) {
        items.map { item ->
            val logs = logsByItem[item.id].orEmpty()
            val result = Forecast.remainingDays(item.currentQuantity, logs, zone)
            RowData(
                item = item,
                category = categoryById[item.categoryId],
                logs = logs,
                expiry = Expiry.status(item.expiryDate, today, leadDays),
                forecast = if (result is ForecastResult.Days && item.currentQuantity > 0.0) Forecast.label(result, item.currentQuantity) else null,
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            value = query.text,
                            onValueChange = { vm.query.value = query.copy(text = it) },
                            placeholder = { Text("品名で検索") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("在庫")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searching) vm.query.value = query.copy(text = "")
                        searching = !searching
                    }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = if (searching) "検索を閉じる" else "検索")
                    }
                    IconButton(onClick = { vm.viewMode.value = if (mode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST }) {
                        if (mode == ViewMode.LIST) {
                            Icon(Icons.Filled.GridView, contentDescription = "グリッドで表示")
                        } else {
                            Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "リストで表示")
                        }
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "並び替え")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            ItemSort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text((if (query.sort == sort) "✓ " else "") + sort.label) },
                                    onClick = {
                                        vm.query.value = query.copy(sort = sort)
                                        sortMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onScan) { Icon(Icons.Filled.QrCodeScanner, contentDescription = "バーコードを読む") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "品目を追加") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            NotificationBanner()
            FilterRow(query, lowCount, categories, ItemFilter.locations(allItems)) { vm.query.value = it }

            when {
                // 並び替えだけを変えた状態は「絞り込んでいない」と数える
                rows.isEmpty() && query.copy(sort = ItemSort.NAME) == ItemQuery() -> EmptyState(
                    icon = Icons.Filled.Inventory2,
                    title = "まだ品目がありません",
                    body = "バーコードを読むか、右下の ＋ から、家の消耗品を登録しましょう。",
                    actionLabel = "バーコードで追加",
                    onAction = onScan,
                )

                rows.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Search,
                    title = "当てはまる品目がありません",
                    body = "検索の文字や絞り込みを変えてみてください。",
                    actionLabel = "絞り込みを解除",
                    onAction = { vm.query.value = ItemQuery(sort = query.sort) },
                )

                mode == ViewMode.LIST -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.item.id }) { row ->
                        ItemRow(row, today, onOpen = { onOpen(row.item.id) }, onStep = { onStep(row.item, it) })
                    }
                }

                // 写真を主役にしたグリッド。列の数は画面の幅に合わせて変わる(1 枚 160dp 以上)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(rows, key = { it.item.id }) { row ->
                        ItemCell(row, today, onOpen = { onOpen(row.item.id) }, onStep = { onStep(row.item, it) })
                    }
                }
            }
        }
    }

    amountFor?.let { (item, type) ->
        AmountDialog(
            title = if (type == ChangeType.CONSUME) "${item.name} を使った量" else "${item.name} を補充した量",
            unit = item.unit,
            withPrice = type == ChangeType.RESTOCK,
            onConfirm = { amount, price ->
                vm.act { changeQuantity(item.id, type, amount, price) }
                amountFor = null
            },
            onDismiss = { amountFor = null },
        )
    }
}

/** FR-03 の通知は許可が無いと出せないので、許可が無い間だけ一覧の上で知らせる。 */
@Composable
private fun NotificationBanner() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        enabled = granted
        // 断られた後は何度も出さない(設定の画面からやり直せる)
        dismissed = true
    }
    if (enabled || dismissed) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "通知が切れています。残り少ない物や期限のお知らせを受けるには、通知を許可してください。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                TextButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("許可する") }
            }
            IconButton(onClick = { dismissed = true }) { Icon(Icons.Filled.Close, contentDescription = "閉じる") }
        }
    }
}

@Composable
private fun FilterRow(
    query: ItemQuery,
    lowCount: Int,
    categories: List<Category>,
    locations: List<String>,
    onChange: (ItemQuery) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LevelFilter.entries.forEach { level ->
            FilterChip(
                selected = query.level == level,
                onClick = { onChange(query.copy(level = level)) },
                // アプリの中のバッジ: 少ない物の数をいつも見せる
                label = { Text(if (level == LevelFilter.LOW) "${level.label}($lowCount)" else level.label) },
            )
        }
        FilterChip(
            selected = query.expiringOnly,
            onClick = { onChange(query.copy(expiringOnly = !query.expiringOnly)) },
            label = { Text("期限が近い") },
        )
        val categoryOptions = listOf<Pair<Long?, String>>(null to "すべてのカテゴリ") +
            categories.map { it.id to it.name } + (ItemFilter.UNCATEGORIZED to "未分類")
        MenuChip(
            label = categoryOptions.firstOrNull { it.first == query.categoryId && it.first != null }?.second ?: "カテゴリ",
            selected = query.categoryId != null,
            options = categoryOptions,
            onPick = { onChange(query.copy(categoryId = it)) },
        )
        if (locations.isNotEmpty()) {
            MenuChip(
                label = query.location ?: "保管場所",
                selected = query.location != null,
                options = listOf<Pair<String?, String>>(null to "すべての場所") + locations.map { it to it },
                onPick = { onChange(query.copy(location = it)) },
            )
        }
        FilterChip(
            selected = query.archived,
            onClick = { onChange(query.copy(archived = !query.archived)) },
            label = { Text("アーカイブ") },
        )
    }
}

/** 押すと選択肢が開くチップ。 */
@Composable
private fun <T> MenuChip(label: String, selected: Boolean, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = selected, onClick = { expanded = true }, label = { Text(label) })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onPick(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** リストの 1 行。右へスワイプで ＋、左へスワイプで −(行は元の位置に戻る)。 */
@Composable
private fun ItemRow(row: RowData, today: LocalDate, onOpen: () -> Unit, onStep: (ChangeType) -> Unit) {
    // スワイプの状態は最初の onStep を覚えてしまうので、常に最新の物を呼ぶ
    val currentOnStep by rememberUpdatedState(onStep)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentOnStep(ChangeType.RESTOCK)
                SwipeToDismissBoxValue.EndToStart -> currentOnStep(ChangeType.CONSUME)
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
        positionalThreshold = { distance -> distance * 0.35f },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val adding = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Row(
                Modifier.fillMaxSize().clip(CardDefaults.shape)
                    .background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (adding) Arrangement.Start else Arrangement.End,
            ) {
                Icon(
                    if (adding) Icons.Filled.Add else Icons.Filled.Remove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        },
    ) {
        Card(onClick = onOpen, colors = cardColors(row.expiry), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ItemThumb(row.item, row.category, 56.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Tags(row)
                    StockBar(row.item, row.logs)
                    StatusLines(row, today)
                }
                StepButton(Icons.Filled.Remove, "減らす", enabled = row.item.currentQuantity > 0.0) { onStep(ChangeType.CONSUME) }
                StepButton(Icons.Filled.Add, "増やす") { onStep(ChangeType.RESTOCK) }
            }
        }
    }
}

/** グリッドの 1 枚。写真を大きく見せる。 */
@Composable
private fun ItemCell(row: RowData, today: LocalDate, onOpen: () -> Unit, onStep: (ChangeType) -> Unit) {
    Card(onClick = onOpen, colors = cardColors(row.expiry)) {
        ItemThumb(row.item, row.category, 160.dp, Modifier.fillMaxWidth().aspectRatio(1.3f), reqPx = 256)
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            StockBar(row.item, row.logs)
            StatusLines(row, today)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StepButton(Icons.Filled.Remove, "減らす", enabled = row.item.currentQuantity > 0.0) { onStep(ChangeType.CONSUME) }
                StepButton(Icons.Filled.Add, "増やす") { onStep(ChangeType.RESTOCK) }
            }
        }
    }
}

/** 期限切れの品目はカードの地の色を変えて目立たせる(FR-09)。文字でも「期限切れ」と書く。 */
@Composable
private fun cardColors(expiry: ExpiryStatus) =
    if (expiry == ExpiryStatus.EXPIRED) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors()
    }

@Composable
private fun Tags(row: RowData) {
    val category = row.category
    val location = row.item.locationTag
    if (category == null && location == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (category != null) {
            val color = Color(category.colorArgb)
            Text(
                category.name,
                style = MaterialTheme.typography.labelSmall,
                color = onColor(color),
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (location != null) {
            Text(location, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** 残りの数と割合、予測、期限。色だけでなく文字で書く。 */
@Composable
private fun StatusLines(row: RowData, today: LocalDate) {
    Text(stockText(row.item, row.logs), style = MaterialTheme.typography.bodySmall)
    row.forecast?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val expiryDate = row.item.expiryDate
    if (expiryDate != null && (row.expiry == ExpiryStatus.SOON || row.expiry == ExpiryStatus.EXPIRED)) {
        val color = if (row.expiry == ExpiryStatus.EXPIRED) MaterialTheme.colorScheme.error else LocalStockColors.current.low
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(Expiry.label(expiryDate, today), style = MaterialTheme.typography.bodySmall, color = color)
        }
    }
}
