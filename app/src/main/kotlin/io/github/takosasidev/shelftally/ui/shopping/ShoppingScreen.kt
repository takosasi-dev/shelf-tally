@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.takosasidev.shelftally.ui.shopping

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.takosasidev.shelftally.core.calc.Format
import io.github.takosasidev.shelftally.core.calc.ShoppingList
import io.github.takosasidev.shelftally.core.calc.Stock
import io.github.takosasidev.shelftally.core.model.ConsumptionLog
import io.github.takosasidev.shelftally.core.model.Item
import io.github.takosasidev.shelftally.core.model.ShoppingListEntry
import io.github.takosasidev.shelftally.data.Purchase
import io.github.takosasidev.shelftally.ui.ShelfViewModel
import io.github.takosasidev.shelftally.ui.components.EmptyState
import kotlinx.coroutines.launch

/**
 * 買い物リスト(FR-04 / FR-05 / FR-15)。低在庫になった品目は自動で入り、手でも足せる。
 * チェックした行は「購入完了」でまとめて在庫に足す。
 */
@Composable
fun ShoppingScreen(vm: ShelfViewModel) {
    val entries by vm.shopping.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val logsByItem by vm.logsByItem.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var newLabel by rememberSaveable { mutableStateOf("") }
    var pickingItem by remember { mutableStateOf(false) }
    var completing by remember { mutableStateOf(false) }
    val itemById = remember(items) { items.associateBy { it.id } }
    val checked = entries.filter { it.isChecked }

    val addFree = {
        val label = newLabel
        newLabel = ""
        vm.act { addFreeEntry(label) }
    }
    val toggle: (ShoppingListEntry) -> Unit = { entry ->
        // チェックは「買った」の手応えを強めに、外すときは軽く
        haptic.performHapticFeedback(if (entry.isChecked) HapticFeedbackType.TextHandleMove else HapticFeedbackType.LongPress)
        vm.act { setChecked(entry.id, !entry.isChecked) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("買い物リスト") },
                actions = {
                    IconButton(onClick = { pickingItem = true }) {
                        Icon(Icons.Filled.Inventory2, contentDescription = "在庫の品目から追加")
                    }
                    IconButton(
                        enabled = entries.isNotEmpty(),
                        onClick = {
                            // FR-15: テキストに整えて、標準の共有シートへ渡す
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, ShoppingList.shareText(entries, itemById))
                            runCatching { context.startActivity(Intent.createChooser(send, "買い物リストを共有")) }
                        },
                    ) { Icon(Icons.Filled.Share, contentDescription = "共有") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newLabel,
                    onValueChange = { newLabel = it },
                    placeholder = { Text("在庫に無い買い物を足す(例: お菓子)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addFree() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = addFree, enabled = newLabel.isNotBlank()) {
                    Icon(Icons.Filled.Add, contentDescription = "追加")
                }
            }

            if (entries.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.ShoppingCart,
                    title = "買い物リストは空です",
                    body = "残りが少なくなった品目は、ここに自動で入ります。在庫の品目を手で足すこともできます。",
                    modifier = Modifier.weight(1f),
                    actionLabel = "在庫の品目から追加",
                    onAction = { pickingItem = true },
                )
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        EntryRow(
                            entry = entry,
                            item = entry.itemId?.let(itemById::get),
                            onToggle = { toggle(entry) },
                            onDelete = { vm.act { deleteEntry(entry.id) } },
                        )
                    }
                }
            }

            if (checked.isNotEmpty()) {
                Button(onClick = { completing = true }, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Text("購入完了(${checked.size}件)", Modifier.padding(start = 6.dp))
                }
            }
        }
    }

    if (pickingItem) {
        val listed = entries.mapNotNull { it.itemId }.toSet()
        val candidates = items.filter { !it.isArchived && it.id !in listed }
        AlertDialog(
            onDismissRequest = { pickingItem = false },
            title = { Text("在庫の品目から追加") },
            text = {
                if (candidates.isEmpty()) {
                    Text("追加できる品目がありません(登録した品目は全部リストに入っています)。")
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(candidates, key = { it.id }) { item ->
                            Text(
                                "${item.name}(残り ${Format.quantity(item.currentQuantity, item.unit)})",
                                modifier = Modifier.fillMaxWidth().clickable {
                                    scope.launch {
                                        // 同じ品目の行が既にあれば足されない(重複させない)
                                        if (!vm.repo.addItemToShopping(item)) snackbar.showSnackbar("買い物リストに既にあります")
                                    }
                                }.padding(vertical = 14.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickingItem = false }) { Text("閉じる") } },
        )
    }

    if (completing) {
        CompleteDialog(
            checked = checked,
            itemById = itemById,
            logsByItem = logsByItem,
            onConfirm = { purchases ->
                completing = false
                vm.act { completePurchase(purchases) }
            },
            onDismiss = { completing = false },
        )
    }
}

/** 買い物リストの 1 行。どちらへスワイプしてもチェックが切り替わる。チェックすると取り消し線が左から伸びる。 */
@Composable
private fun EntryRow(entry: ShoppingListEntry, item: Item?, onToggle: () -> Unit, onDelete: () -> Unit) {
    val currentOnToggle by rememberUpdatedState(onToggle)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) currentOnToggle()
            false
        },
        positionalThreshold = { distance -> distance * 0.35f },
    )
    val strike by animateFloatAsState(if (entry.isChecked) 1f else 0f, tween(250), label = "strike")
    val textColor = if (entry.isChecked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
        },
    ) {
        Surface(Modifier.fillMaxWidth()) {
            Row(Modifier.clickable(onClick = onToggle).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = entry.isChecked, onCheckedChange = { onToggle() })
                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(
                        item?.name ?: entry.label,
                        color = textColor,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.drawWithContent {
                            drawContent()
                            if (strike > 0f) {
                                val y = size.height / 2
                                drawLine(textColor, Offset(0f, y), Offset(size.width * strike, y), strokeWidth = 2.dp.toPx())
                            }
                        },
                    )
                    val note = listOfNotNull(
                        item?.let { "残り ${Format.quantity(it.currentQuantity, it.unit)}" },
                        "自動で追加".takeIf { entry.isAutoGenerated },
                    ).joinToString(" ・ ")
                    if (note.isNotEmpty()) {
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "リストから消す") }
            }
        }
    }
}

/**
 * FR-05 の購入完了。在庫の品目は、足す量を「満タンまでの差」で提案し、手で直せる(金額は任意)。
 * 量を空にした品目は、在庫を変えずにリストから消すだけ。
 */
@Composable
private fun CompleteDialog(
    checked: List<ShoppingListEntry>,
    itemById: Map<Long, Item>,
    logsByItem: Map<Long, List<ConsumptionLog>>,
    onConfirm: (List<Purchase>) -> Unit,
    onDismiss: () -> Unit,
) {
    val stocked = remember(checked, itemById) { checked.mapNotNull { e -> e.itemId?.let(itemById::get) } }
    val amounts = remember {
        mutableStateMapOf<Long, String>().apply {
            stocked.forEach { put(it.id, Format.quantity(Stock.suggestedRestock(it, logsByItem[it.id].orEmpty()))) }
        }
    }
    val prices = remember { mutableStateMapOf<Long, String>() }
    val others = checked.size - stocked.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("購入完了") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(
                        "買った量を在庫に足し、チェックした行をリストから消します。" +
                            if (others > 0) "在庫に無い買い物($others 件)は、リストから消すだけです。" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(stocked, key = { it.id }) { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${item.name}(今 ${Format.quantity(item.currentQuantity, item.unit)})", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = amounts[item.id].orEmpty(),
                                onValueChange = { amounts[item.id] = it },
                                label = { Text("足す量(${item.unit})") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = prices[item.id].orEmpty(),
                                onValueChange = { prices[item.id] = it.filter(Char::isDigit).take(8) },
                                label = { Text("金額(円)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    stocked.mapNotNull { item ->
                        val amount = Format.parseQuantity(amounts[item.id].orEmpty())?.takeIf { it > 0.0 } ?: return@mapNotNull null
                        Purchase(item.id, amount, prices[item.id]?.toIntOrNull())
                    },
                )
            }) { Text("在庫に反映") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
