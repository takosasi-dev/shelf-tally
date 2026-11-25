@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.takosasidev.shelftally.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Category as CategoryIcon
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.takosasidev.shelftally.core.model.Category
import io.github.takosasidev.shelftally.ui.ShelfViewModel
import io.github.takosasidev.shelftally.ui.components.EmptyState
import io.github.takosasidev.shelftally.ui.theme.ThemeColorChoices

/**
 * FR-13: カテゴリの作成・編集・削除・並び替えと、色の設定。
 * カテゴリの表(要件定義書 §4)にアイコンの列は無いので、ここで決められるのは名前と色。アイコンは品目ごとに選ぶ。
 */
@Composable
fun CategoryScreen(vm: ShelfViewModel, onBack: () -> Unit) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("カテゴリ") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = Category(name = "", colorArgb = ThemeColorChoices.first().second.toArgb(), sortOrder = 0)
            }) { Icon(Icons.Filled.Add, contentDescription = "カテゴリを追加") }
        },
    ) { padding ->
        if (categories.isEmpty()) {
            EmptyState(Icons.Filled.CategoryIcon,"カテゴリがありません", "右下の ＋ から作れます。", Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 88.dp)) {
                itemsIndexed(categories, key = { _, c -> c.id }) { index, category ->
                    Row(
                        Modifier.clickable { editing = category }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(category.colorArgb)))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(category.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${items.count { it.categoryId == category.id }} 件の品目",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(enabled = index > 0, onClick = { vm.act { moveCategory(category.id, up = true) } }) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "上へ")
                        }
                        IconButton(enabled = index < categories.lastIndex, onClick = { vm.act { moveCategory(category.id, up = false) } }) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "下へ")
                        }
                        IconButton(onClick = { deleting = category }) { Icon(Icons.Filled.Delete, contentDescription = "削除") }
                    }
                }
            }
        }
    }

    editing?.let { target ->
        EditDialog(
            target,
            onSave = {
                vm.act { saveCategory(it) }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { target ->
        val count = items.count { it.categoryId == target.id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("「${target.name}」を削除しますか") },
            // 品目が属しているときは、中止するか、未分類に移して消すかを選ばせる
            text = {
                Text(if (count > 0) "このカテゴリには $count 件の品目があります。削除すると、その品目は「未分類」に移ります(品目は消えません)。" else "このカテゴリに品目はありません。")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.act { deleteCategory(target.id) }
                    deleting = null
                }) { Text(if (count > 0) "未分類に移して削除" else "削除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("中止") } },
        )
    }
}

@Composable
private fun EditDialog(original: Category, onSave: (Category) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(original.name) }
    var color by remember { mutableStateOf(original.colorArgb) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (original.id == 0L) "カテゴリを追加" else "カテゴリを編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名前") }, singleLine = true)
                ColorChoices(selected = color, onPick = { color = it })
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(original.copy(name = name.trim(), colorArgb = color)) }) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** 色の候補。選んでいる色は輪で囲む(色だけでなく、読み上げにも名前を渡す)。設定の画面でも使う。 */
@Composable
fun ColorChoices(selected: Int?, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ThemeColorChoices.forEach { (label, color) ->
            val argb = color.toArgb()
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClickLabel = label) { onPick(argb) }
                    .then(if (selected == argb) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .padding(8.dp).clip(CircleShape).background(color),
            )
        }
    }
}
