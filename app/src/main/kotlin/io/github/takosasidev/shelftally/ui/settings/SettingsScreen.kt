@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package io.github.takosasidev.shelftally.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import io.github.takosasidev.shelftally.ui.components.groupShape

/** 設定(FR-21 / FR-22 / FR-23 / FR-25)と、カテゴリ・バックアップへの入口(NFR-04)。 */
@Composable
fun SettingsScreen(vm: ShelfViewModel, onOpenCategories: () -> Unit, onOpenBackup: () -> Unit) {
    val loaded by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val update: ((AppSettings) -> AppSettings) -> Unit = { change -> vm.act { updateSettings(change) } }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { LargeFlexibleTopAppBar(title = { Text("設定") }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        val s = loaded
        if (s != null) {
            Column(
                Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp),
            ) {
                Group(
                    "表示",
                    {
                        Choice(
                            "テーマの色",
                            listOf(
                                ThemeColorMode.DYNAMIC to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "壁紙に合わせる" else "標準"),
                                ThemeColorMode.MANUAL to "自分で選ぶ",
                            ),
                            s.themeColorMode,
                        ) { mode -> update { it.copy(themeColorMode = mode) } }
                        if (s.themeColorMode == ThemeColorMode.MANUAL) {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                                ColorChoices(selected = s.manualThemeColor, onPick = { color -> update { it.copy(manualThemeColor = color) } })
                            }
                        }
                    },
                    {
                        Choice(
                            "ダークモード",
                            listOf(DarkModeMode.SYSTEM to "端末と同じ",DarkModeMode.LIGHT to "ライト", DarkModeMode.DARK to "ダーク"),
                            s.darkModeMode,
                        ) { mode -> update { it.copy(darkModeMode = mode) } }
                    },
                    {
                        Choice(
                            "一覧の最初の形",
                            listOf(ViewMode.LIST to "リスト", ViewMode.GRID to "グリッド"),
                            s.defaultViewMode,
                        ) { mode ->
                            update { it.copy(defaultViewMode = mode) }
                            // 一覧で手動で切り替えた形が残っていると、設定を変えても見た目が変わらない
                            vm.viewMode.value = null
                        }
                    },
                )

                Group("お知らせ", {
                    Stepper("期限のお知らせ", s.expiryAlertLeadDays, 0..30, "日前", Icons.Outlined.Notifications) { v ->
                        update { it.copy(expiryAlertLeadDays = v) }
                    }
                })

                Group(
                    "データ",
                    { NavRow(Icons.Outlined.Category, "カテゴリの管理", "名前・色・並び順", onOpenCategories) },
                    { NavRow(Icons.Outlined.Backup, "バックアップと復元", "zip に書き出す・読み込む・自動バックアップ", onOpenBackup) },
                )

                Group("セキュリティ", {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        LeadingIcon(Icons.Outlined.Lock)
                        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                            Text("アプリロック", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "開くときに端末のロック解除(指紋・顔・PIN など)を求めます。端末に画面ロックが無いときは働きません。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = s.appLockEnabled, onCheckedChange = { on -> update { it.copy(appLockEnabled = on) } })
                    }
                })

                Group("このアプリについて", {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        LeadingIcon(Icons.Outlined.Info)
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text("ShelfTally ${BackupManager.appVersionName(context)}", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "完全オフライン。記録と写真は端末の中だけに保存し、外へ送りません。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                })
            }
        }
    }
}

/** 見出しと、角の丸いまとまり。行どうしは細い隙間で区切る(Android 16 の設定の画面と同じ形)。 */
@Composable
internal fun Group(title: String, vararg rows: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEachIndexed { i, row ->
            Surface(Modifier.fillMaxWidth(), shape = groupShape(i, rows.size), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column { row() }
            }
        }
    }
}

/** 行の頭の丸いアイコン。 */
@Composable
internal fun LeadingIcon(icon: ImageVector) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp)) }
}


@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onPick(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    // チェックの印は出さない(選んだ物は地の色で分かる。3 つ並ぶと文字が欠けるため)
                    icon = {},
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

/** 数を −/＋ で変える行。 */
@Composable
internal fun Stepper(title: String, value: Int, range: IntRange, suffix: String, icon: ImageVector? = null, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) LeadingIcon(icon)
        Text(title, Modifier.weight(1f).padding(start = if (icon != null) 16.dp else 0.dp), style = MaterialTheme.typography.bodyLarge)
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
private fun NavRow(icon: ImageVector, title: String, note: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeadingIcon(icon)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
