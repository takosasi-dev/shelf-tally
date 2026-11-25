// FR-25 アプリロック。書き方は収支管理アプリ(win-loss-tracker)の AppLockGate と同じ。
package io.github.takosasidev.shelftally.ui.lock

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 既定は OFF。ON のときは、端末のロック解除の画面(KeyguardManager)を出す。
 * 指紋や顔が登録されていれば生体認証を、無ければ PIN / パターン / パスワードを受け付ける
 * (生体認証だけにすると、登録していない端末で締め出される)。
 * androidx.biometric の BiometricPrompt は FragmentActivity が要るので使っていない(依存も足さない)。
 */
@Composable
fun AppLockGate(enabled: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val keyguard = remember(context) { context.getSystemService(KeyguardManager::class.java) }

    // 画面ロックが未設定なら認証のしようがない。ここで閉じると設定を戻す手段ごと失うので、素通しにする
    val canLock = remember(keyguard) { keyguard?.isDeviceSecure == true }

    // 画面の回転では保ち、プロセスが終わったらまたロックする
    var unlocked by rememberSaveable { mutableStateOf(false) }

    if (!enabled || !canLock || unlocked) {
        content()
        return
    }

    var prompting by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        prompting = false
        if (result.resultCode == Activity.RESULT_OK) unlocked = true
    }

    @Suppress("DEPRECATION") // API 29 で非推奨だが、FragmentActivity 無しで端末の認証を出す手段はこれだけ
    fun prompt() {
        if (prompting) return
        val intent = runCatching {
            keyguard?.createConfirmDeviceCredentialIntent("ロックを解除", "在庫のデータを表示するには、端末のロック解除が要ります")
        }.getOrNull()
        if (intent == null) {
            // 端末の状態が変わって認証を出せなくなった。閉じ込めずに通す
            unlocked = true
            return
        }
        prompting = true
        runCatching { launcher.launch(intent) }.onFailure { prompting = false }
    }

    // 開いた直後に 1 回だけ自動で認証を出す。その後は「ロックを解除」を押す
    LaunchedEffect(Unit) { prompt() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text("ロックされています", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
            Text(
                "端末のロックを解除すると、在庫のデータを表示します。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = { prompt() }, enabled = !prompting, modifier = Modifier.padding(top = 24.dp)) { Text("ロックを解除") }
        }
    }
}
