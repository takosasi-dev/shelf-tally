@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.takosasidev.shelftally.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.takosasidev.shelftally.core.backup.BackupContent
import io.github.takosasidev.shelftally.data.backup.BackupManager
import io.github.takosasidev.shelftally.data.backup.BackupReadResult
import io.github.takosasidev.shelftally.ui.ShelfViewModel
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val StampFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault())

/** 読み込みの途中の段階。 */
private sealed interface ImportStage {
    data class Password(val uri: Uri, val message: String?) : ImportStage
    data class Confirm(val content: BackupContent) : ImportStage
    data class Failed(val message: String) : ImportStage
}

/**
 * FR-18(書き出し・読み込み)、FR-19(パスワード)、FR-20(自動バックアップ)。
 * ファイルの場所は SAF(端末のファイル選択)で選ぶので、ストレージの権限は要らない。
 */
@Composable
fun BackupScreen(vm: ShelfViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val loaded by vm.settings.collectAsStateWithLifecycle()

    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<ImportStage?>(null) }
    var treeName by remember { mutableStateOf(BackupManager.treeUri(context)?.let { BackupManager.treeDisplayName(context, it) }) }
    var autoError by remember { mutableStateOf(BackupManager.lastAutoBackupError(context)) }

    val read: (Uri, String?) -> Unit = { uri, pw ->
        busy = true
        scope.launch {
            stage = when (val result = BackupManager.readBackup(context, uri, pw)) {
                is BackupReadResult.Ok -> ImportStage.Confirm(result.content)
                BackupReadResult.NeedPassword -> ImportStage.Password(uri, null)
                is BackupReadResult.WrongPassword -> ImportStage.Password(uri, result.message)
                is BackupReadResult.Incompatible -> ImportStage.Failed(result.message)
                is BackupReadResult.Error -> ImportStage.Failed(result.message)
            }
            busy = false
        }
    }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.ZIP_MIME)) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val result = runCatching { BackupManager.exportToUri(context, uri, password.ifEmpty { null }) }
                busy = false
                snackbar.showSnackbar(
                    if (result.isSuccess) {
                        if (password.isEmpty()) "バックアップを書き出しました" else "パスワード付きでバックアップを書き出しました"
                    } else {
                        "書き出しに失敗しました: ${result.exceptionOrNull()?.message}"
                    },
                )
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) read(uri, null)
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (BackupManager.persistTree(context, uri)) {
                treeName = BackupManager.treeDisplayName(context, uri)
                BackupManager.setLastAutoBackupError(context, null)
                autoError = null
            } else {
                scope.launch { snackbar.showSnackbar("このフォルダは保存先にできませんでした。別のフォルダを選んでください。") }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("バックアップと復元") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Header("書き出す")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "品目・カテゴリ・履歴・買い物リスト・設定・写真を 1 つの zip にします。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("パスワード(任意)") },
                    supportingText = { Text("入れると zip を暗号化します(AES-256)。忘れると読み込めません") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(enabled = !busy, onClick = { createBackup.launch(BackupManager.manualFileName()) }) { Text("zip に書き出す") }
            }

            Header("読み込む")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("今のデータを全部消して、バックアップの中身に入れ替えます。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(enabled = !busy, onClick = { openBackup.launch(arrayOf(BackupManager.ZIP_MIME, "application/octet-stream")) }) {
                    Text("zip を選んで読み込む")
                }
            }

            Header("自動バックアップ")
            val s = loaded
            if (s != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("アプリを開いたときに自動で取る")
                        Text(
                            "前のバックアップから決めた日数が過ぎていたら、開いたときに保存先へ書き出します(パスワードなし)。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = s.autoBackupEnabled, onCheckedChange = { on -> vm.act { updateSettings { it.copy(autoBackupEnabled = on) } } })
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("保存先: ${treeName ?: "未設定"}", Modifier.weight(1f))
                    TextButton(onClick = { pickTree.launch(null) }) { Text("選ぶ") }
                }
                Stepper("間隔", s.autoBackupIntervalDays, 1..90, "日ごと") { v -> vm.act { updateSettings { it.copy(autoBackupIntervalDays = v) } } }
                Stepper("残す数", s.autoBackupKeepCount, 1..30, "個") { v -> vm.act { updateSettings { it.copy(autoBackupKeepCount = v) } } }
                Text(
                    "最後の自動バックアップ: ${s.lastAutoBackupAt?.let(StampFmt::format) ?: "まだありません"}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (s.autoBackupEnabled && treeName == null) {
                    Text(
                        "保存先のフォルダを選ぶまで、自動バックアップは取られません。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                autoError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    when (val current = stage) {
        is ImportStage.Password -> {
            var input by remember(current) { mutableStateOf("") }
            val message = current.message
            val hint: (@Composable () -> Unit)? = if (message != null) {
                { Text(message) }
            } else {
                null
            }
            AlertDialog(
                onDismissRequest = { stage = null },
                title = { Text("パスワード") },
                text = {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("このバックアップのパスワード") },
                        supportingText = hint,
                        isError = message != null,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                },
                confirmButton = { TextButton(enabled = input.isNotEmpty() && !busy, onClick = { read(current.uri, input) }) { Text("読み込む") } },
                dismissButton = { TextButton(onClick = { stage = null }) { Text("キャンセル") } },
            )
        }

        is ImportStage.Confirm -> {
            val c = current.content
            AlertDialog(
                onDismissRequest = { stage = null },
                title = { Text("今のデータを入れ替えますか") },
                text = {
                    Text(
                        "バックアップの中身: 品目 ${c.items.size} 件、カテゴリ ${c.categories.size} 件、履歴 ${c.logs.size} 件、" +
                            "買い物リスト ${c.shopping.size} 件、写真 ${c.photoCount} 枚" +
                            (c.exportedAt?.let { "(${StampFmt.format(it)} に書き出し)" } ?: "") +
                            "。\n\n今このアプリにあるデータは全部消えて、元に戻せません。",
                    )
                },
                confirmButton = {
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            val result = runCatching { BackupManager.restore(context, c) }
                            busy = false
                            stage = null
                            snackbar.showSnackbar(if (result.isSuccess) "復元しました" else "復元に失敗しました。データは元のままです: ${result.exceptionOrNull()?.message}")
                        }
                    }) { Text("入れ替える", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { stage = null }) { Text("キャンセル") } },
            )
        }

        is ImportStage.Failed -> AlertDialog(
            onDismissRequest = { stage = null },
            title = { Text("読み込めませんでした") },
            text = { Text(current.message) },
            confirmButton = { TextButton(onClick = { stage = null }) { Text("閉じる") } },
        )

        null -> Unit
    }
}
