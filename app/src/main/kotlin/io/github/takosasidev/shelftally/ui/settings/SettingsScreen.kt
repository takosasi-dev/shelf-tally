@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.takosasidev.shelftally.ui.settings

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.takosasidev.shelftally.core.model.AppSettings
import io.github.takosasidev.shelftally.core.model.DarkModeMode
import io.github.takosasidev.shelftally.core.model.ThemeColorMode
import io.github.takosasidev.shelftally.core.model.ViewMode
import io.github.takosasidev.shelftally.data.backup.BackupManager
import io.github.takosasidev.shelftally.ui.ShelfViewModel
import io.github.takosasidev.shelftally.ui.category.ColorChoices

/** 設定(FR-21 / FR-22 / FR-23 / FR-25)と、カテゴリ・バックアップへの入口(NFR-04)。 */
@Composable
fun SettingsScreen(vm: ShelfViewModel, onOpenCategories: () -> Unit, onOpenBackup: () -> Unit) {
    val loaded by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val update: ((AppSettings) -> AppSettings) -> Unit = { change -> vm.act { updateSettings(change) } }

    Scaffold(topBar = { TopAppBar(title = { Text("設定") }) }) { padding ->
        val s = loaded
        if (s != null) {
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Header("表示")
                Choice(
                    "テーマの色",
                    listOf(
                        ThemeColorMode.DYNAMIC to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "壁紙に合わせる" else "標準"),
                        ThemeColorMode.MANUAL to "自分で選ぶ",
                    ),
                    s.themeColorMode,
                ) { mode -> update { it.copy(themeColorMode = mode) } }
                if (s.themeColorMode == ThemeColorMode.MANUAL) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        ColorChoices(selected = s.manualThemeColor, onPick = { color -> update { it.copy(manualThemeColor = color) } })
                    }
                }
                Choice(
                    "ダークモード",
                    listOf(DarkModeMode.SYSTEM to "端末に合わせる", DarkModeMode.LIGHT to "ライト", DarkModeMode.DARK to "ダーク"),
                    s.darkModeMode,
                ) { mode -> update { it.copy(darkModeMode = mode) } }
                Choice(
                    "一覧の最初の形",
                    listOf(ViewMode.LIST to "リスト", ViewMode.GRID to "グリッド"),
                    s.defaultViewMode,
                ) { mode ->
                    update { it.copy(defaultViewMode = mode) }
                    // 一覧で手動で切り替えた形が残っていると、設定を変えても見た目が変わらない
                    vm.viewMode.value = null
                }

                Header("お知らせ")
                Stepper("期限の何日前に知らせるか", s.expiryAlertLeadDays, 0..30, "日前") { v -> update { it.copy(expiryAlertLeadDays = v) } }

                Header("データ")
                NavRow("カテゴリの管理", "名前・色・並び順", onOpenCategories)
                NavRow("バックアップと復元", "zip に書き出す・読み込む・自動バックアップ", onOpenBackup)

                Header("セキュリティ")
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("アプリロック")
                        Text(
                            "開くときに端末のロック解除(指紋・顔・PIN など)を求めます。端末に画面ロックが無いときは働きません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.appLockEnabled, onCheckedChange = { on -> update { it.copy(appLockEnabled = on) } })
                }

                Header("このアプリについて")
                Text(
                    "ShelfTally ${BackupManager.appVersionName(context)}\n完全オフライン。記録と写真は端末の中だけに保存し、外へ送りません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
internal fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(title)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onPick(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

/** 数を −/＋ で変える行。 */
@Composable
internal fun Stepper(title: String, value: Int, range: IntRange, suffix: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        IconButton(enabled = value > range.first, onClick = { onChange(value - 1) }) {
            Icon(Icons.Filled.Remove, contentDescription = "減らす")
        }
        Text("$value$suffix")
        IconButton(enabled = value < range.last, onClick = { onChange(value + 1) }) {
            Icon(Icons.Filled.Add, contentDescription = "増やす")
        }
    }
}

@Composable
private fun NavRow(title: String, note: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
    }
}
