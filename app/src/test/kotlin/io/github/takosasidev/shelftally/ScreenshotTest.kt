package io.github.takosasidev.shelftally

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.takosasidev.shelftally.core.model.AppSettings
import io.github.takosasidev.shelftally.core.model.ChangeType
import io.github.takosasidev.shelftally.core.model.ConsumptionLog
import io.github.takosasidev.shelftally.core.model.DarkModeMode
import io.github.takosasidev.shelftally.core.model.Item
import io.github.takosasidev.shelftally.core.model.ThemeColorMode
import io.github.takosasidev.shelftally.core.model.ViewMode
import io.github.takosasidev.shelftally.data.ShelfRepository
import io.github.takosasidev.shelftally.ui.ShelfViewModel
import io.github.takosasidev.shelftally.ui.category.CategoryScreen
import io.github.takosasidev.shelftally.ui.items.ItemDetailScreen
import io.github.takosasidev.shelftally.ui.items.ItemEditScreen
import io.github.takosasidev.shelftally.ui.settings.BackupScreen
import io.github.takosasidev.shelftally.ui.items.ItemListScreen
import io.github.takosasidev.shelftally.ui.settings.SettingsScreen
import io.github.takosasidev.shelftally.ui.shopping.ShoppingScreen
import io.github.takosasidev.shelftally.ui.stats.StatsScreen
import io.github.takosasidev.shelftally.ui.theme.ShelfTallyTheme
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * 画面を PC の上で画像にする(端末が無くても見た目を確かめるため)。
 * `gradlew testDebugUnitTest -Pshots` のときだけ app/build/outputs/roborazzi/ に PNG を書く。
 * 画像は比べない(合否を見ない)。見るのは人。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ScreenshotTest {

    // テスト用の空の Activity を、マニフェストに書かずに登録する(debug の APK に物を足さないため)
    @get:Rule(order = 0)
    val registerActivity = object : TestWatcher() {
        override fun starting(description: Description?) {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager)
                .addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** 見た目を確かめたい状態(少ない・切れ・期限切れ・長い名前・数か月の記録・買い物)を一通り入れる。 */
    private fun seed(repo: ShelfRepository): Long = runBlocking {
        repo.ensureDefaults()
        val cats = repo.categories.first()
        val now = Instant.now()
        val today = LocalDate.now()
        fun item(name: String, qty: Double, unit: String, low: Double, cat: Int, icon: String, place: String, expiry: LocalDate? = null) =
            Item(
                name = name, currentQuantity = qty, unit = unit, lowStockThreshold = low,
                categoryId = cats.getOrNull(cat)?.id, iconKey = icon, expiryDate = expiry,
                locationTag = place, createdAt = now,
            )
        val paper = repo.saveItem(item("トイレットペーパー", 16.0, "ロール", 4.0, 0, "box", "洗面所"))
        val detergent = repo.saveItem(item("食器用洗剤", 1.0, "本", 1.0, 0, "cleaning", "台所"))
        val soap = repo.saveItem(item("ハンドソープ(詰め替え)", 0.0, "袋", 1.0, 0, "soap", "洗面所"))
        val bodySoap = repo.saveItem(item("ボディソープ(しっとりタイプ・大容量の詰め替え用)", 2.0, "袋", 1.0, 0, "soap", "浴室"))
        val milk = repo.saveItem(item("牛乳", 2.0, "本", 1.0, 1, "drink", "冷蔵庫", today.plusDays(2)))
        repo.saveItem(item("ヨーグルト", 1.0, "個", 1.0, 1, "food", "冷蔵庫", today.minusDays(1)))
        val rice = repo.saveItem(item("米", 3.5, "kg", 2.0, 1, "food", "台所"))
        val cat = repo.saveItem(item("キャットフード", 6.0, "袋", 2.0, 2, "pet", "物置"))
        repo.saveItem(item("頭痛薬", 12.0, "錠", 6.0, 3, "medicine", "リビング"))

        // 統計を埋めるため、6 か月分の補充と直近 30 日の消費を過去の日付で入れる(バックアップからの復元と同じ道を通す)
        fun ago(days: Long) = now.minus(Duration.ofDays(days))
        val logs = buildList {
            for (m in 0L..5L) {
                add(ConsumptionLog(itemId = paper, changeType = ChangeType.RESTOCK, changeAmount = 12.0, price = 598 + (m % 3).toInt() * 40, recordedAt = ago(m * 30 + 3)))
                if (m % 2 == 0L) add(ConsumptionLog(itemId = rice, changeType = ChangeType.RESTOCK, changeAmount = 5.0, price = 2480, recordedAt = ago(m * 30 + 9)))
                if (m != 4L) add(ConsumptionLog(itemId = cat, changeType = ChangeType.RESTOCK, changeAmount = 3.0, price = 1180, recordedAt = ago(m * 30 + 14)))
            }
            add(ConsumptionLog(itemId = milk, changeType = ChangeType.RESTOCK, changeAmount = 2.0, price = 438, recordedAt = ago(4)))
            listOf(paper to 1.0, paper to 2.0, paper to 1.0, detergent to 1.0, soap to 1.0, bodySoap to 1.0, milk to 1.0, milk to 1.0, rice to 1.5, cat to 1.0)
                .forEachIndexed { i, (id, amount) ->
                    add(ConsumptionLog(itemId = id, changeType = ChangeType.CONSUME, changeAmount = amount, recordedAt = ago(2L + i * 2)))
                }
        }
        // 復元は id で重複を除くので、1 から振る
        repo.replaceAll(repo.snapshot().copy(logs = logs.mapIndexed { i, log -> log.copy(id = i + 1L) }))

        val items = repo.itemsOnce()
        repo.addItemToShopping(items.first { it.id == detergent })
        repo.addItemToShopping(items.first { it.id == soap })
        repo.addFreeEntry("お菓子")
        repo.addFreeEntry("電池(単3)")
        repo.shoppingOnce().first { it.label == "お菓子" }.let { repo.setChecked(it.id, true) }
        println("SEED shopping=${repo.shoppingOnce().size} logs=${logs.size}")
        paper
    }

    @Test
    fun 画面を撮る() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = ShelfRepository.get(app)
        val firstId = seed(repo)
        val vm = ShelfViewModel(repo)
        // Robolectric では Room の最初の結果が届くのに時間がかかるので、買い物リストは最初から読み始めておく
        vm.viewModelScope.launch { vm.shopping.collect {} }
        val list: @Composable () -> Unit = { ItemListScreen(vm, {}, {}, {}) }
        val screens = mapOf<String, @Composable () -> Unit>(
            "items" to list,
            "grid" to list,
            "detail" to { ItemDetailScreen(vm, firstId, {}, {}) },
            "shopping" to { ShoppingScreen(vm) },
            "stats" to { StatsScreen(vm) },
            "settings" to { SettingsScreen(vm, {}, {}) },
            "edit" to { ItemEditScreen(vm, firstId, "", {}, {}) },
            "categories" to { CategoryScreen(vm, {}) },
            "backup" to { BackupScreen(vm, {}) },
        )
        val teal = 0xFF00796B.toInt()
        var dark by mutableStateOf(false)
        var name by mutableStateOf("")
        var seedColor by mutableStateOf<Int?>(teal)
        var themeMode by mutableStateOf(ThemeColorMode.MANUAL)
        compose.setContent {
            ShelfTallyTheme(
                AppSettings(
                    themeColorMode = themeMode,
                    manualThemeColor = seedColor,
                    darkModeMode = if (dark) DarkModeMode.DARK else DarkModeMode.LIGHT,
                ),
            ) {
                // key で画面ごとに状態を作り直す(前の画面の remember を持ち越さない)
                key(name, seedColor) { screens[name]?.invoke() }
            }
        }
        fun shoot(file: String) {
            // Room は別のスレッドで読むので、その画面が使う物が届くまで待つ
            runCatching {
                compose.waitUntil(20_000) {
                    vm.items.value.isNotEmpty() && (!file.startsWith("shopping") || vm.shopping.value.isNotEmpty())
                }
            }.onFailure { println("SHOT $file: 届かない items=${vm.items.value.size} shopping=${vm.shopping.value.size}") }
            compose.mainClock.advanceTimeBy(1_000) // 残量バーなどの動きを終わらせる
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/$file.png")
        }
        for (n in screens.keys) for (d in listOf(false, true)) {
            vm.viewMode.value = if (n == "grid") ViewMode.GRID else ViewMode.LIST
            dark = d
            name = n
            shoot("$n-${if (d) "dark" else "light"}")
            // 縦に長い画面は、下まで送った所も撮る
            if (n == "detail" || n == "settings" || n == "edit" || n == "backup") {
                repeat(3) { compose.onRoot().performTouchInput { swipeUp() } }
                shoot("$n-${if (d) "dark" else "light"}-bottom")
            }
        }
        // 既定の設定(壁紙の色に合わせる)。依頼者の端末(Android 12 以上)ではこちらの見た目になる
        themeMode = ThemeColorMode.DYNAMIC
        for (n in listOf("items", "stats")) for (d in listOf(false, true)) {
            vm.viewMode.value = ViewMode.LIST
            dark = d
            name = n
            shoot("$n-dynamic-${if (d) "dark" else "light"}")
        }
        themeMode = ThemeColorMode.MANUAL
        // 色の候補ごとの見え方(残量の色とぶつからないか)。null は「壁紙の色が無い端末で、色を選んでいない」とき
        vm.viewMode.value = ViewMode.LIST
        name = "items"
        dark = false
        for ((label, c) in listOf("orange" to 0xFFE07A2F.toInt(), "rose" to 0xFFB3325B.toInt(), "fallback" to null)) {
            seedColor = c
            shoot("items-$label-light")
        }
    }
}
