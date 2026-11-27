package io.github.takosasidev.shelftally

import android.view.ViewGroup
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.takosasidev.shelftally.core.calc.Stock
import io.github.takosasidev.shelftally.core.model.AppSettings
import io.github.takosasidev.shelftally.data.ShelfRepository
import io.github.takosasidev.shelftally.data.backup.AutoBackup
import io.github.takosasidev.shelftally.notify.DailyCheck
import io.github.takosasidev.shelftally.notify.DailyCheckScheduler
import io.github.takosasidev.shelftally.ui.ShelfViewModel
import io.github.takosasidev.shelftally.ui.category.CategoryScreen
import io.github.takosasidev.shelftally.ui.items.ItemDetailScreen
import io.github.takosasidev.shelftally.ui.items.ItemEditScreen
import io.github.takosasidev.shelftally.ui.items.ItemListScreen
import io.github.takosasidev.shelftally.ui.lock.AppLockGate
import io.github.takosasidev.shelftally.ui.nav.Routes
import io.github.takosasidev.shelftally.ui.scan.ScanScreen
import io.github.takosasidev.shelftally.ui.settings.BackupScreen
import io.github.takosasidev.shelftally.ui.settings.SettingsScreen
import io.github.takosasidev.shelftally.ui.shopping.ShoppingScreen
import io.github.takosasidev.shelftally.ui.stats.StatsScreen
import io.github.takosasidev.shelftally.ui.theme.ShelfTallyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 起動時の処理を動かす場所。画面の lifecycleScope で動かすと、回転などの作り直しで自動バックアップの
 * 書き出しが途中で切られる。作り直しのたびに重ねて走らないことは、AutoBackup と DailyCheck の側で守る。
 */
private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class MainActivity : ComponentActivity() {

    /**
     * ショートカット・通知から渡された Intent。`setIntent()` は再コンポーズを起こさないので、
     * State として持ち、処理したら null に戻す(起動中に来た分も飛べるように、二重に飛ばないように)。
     */
    private val pendingIntent = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Android 8.1(API 27)までは、OS が画面の最初の入力欄へ勝手にフォーカスを渡し、キーボードが出る。
        // 入れ物の側でフォーカスを受けて、入力欄には押した時だけ渡す
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            findViewById<ViewGroup>(android.R.id.content).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            }
        }
        // 回転などで作り直されたときは、同じ Intent をもう一度処理しない
        // (ショートカットで開いた後に回すたび、バーコードの画面へ飛び直してしまう)
        if (savedInstanceState == null) pendingIntent.value = intent

        // NFR-06: 起動時の自動バックアップの判定と期限の見回りは IO のスレッドで行い、画面を待たせない。
        // どれが失敗しても、アプリの起動は妨げない。
        val app = applicationContext
        startupScope.launch {
            runCatching { ShelfRepository.get(app).ensureDefaults() }
            runCatching { AutoBackup.runIfDue(app) }
            runCatching { DailyCheck.run(app) }
            // 再起動でアラームは消えるので、開くたびに積み直す
            runCatching { DailyCheckScheduler.schedule(app) }
        }

        setContent {
            val vm: ShelfViewModel = viewModel(factory = ShelfViewModel.factory(applicationContext))
            val settings by vm.settings.collectAsStateWithLifecycle()
            ShelfTallyTheme(settings ?: AppSettings()) {
                val loaded = settings
                if (loaded == null) {
                    // 設定(アプリロックの有無)が届くまで、中身を出さない
                    Surface(Modifier.fillMaxSize()) {}
                } else {
                    AppLockGate(enabled = loaded.appLockEnabled) {
                        val navController = rememberNavController()
                        AppShell(vm, navController)
                        HandleLaunchIntent(navController, pendingIntent.value) { pendingIntent.value = null }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent.value = intent
    }
}

/** 下のナビに出す行き先。ここに無い画面は掘り下げた先として、ナビを隠す。 */
private enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    ITEMS(Routes.ITEMS, "在庫", Icons.Filled.Inventory2),
    SHOPPING(Routes.SHOPPING, "買い物", Icons.Filled.ShoppingCart),
    STATS(Routes.STATS, "統計", Icons.Filled.BarChart),
    SETTINGS(Routes.SETTINGS, "設定", Icons.Filled.Settings),
}

private fun NavHostController.openTab(route: String) = navigate(route) {
    // タブを行き来しても戻る道を積み上げない
    popUpTo(Routes.ITEMS) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun AppShell(vm: ShelfViewModel, navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val items by vm.items.collectAsStateWithLifecycle()
    val shopping by vm.shopping.collectAsStateWithLifecycle()
    // FR-03 のアプリの中のバッジ: 少ない品目の数と、まだ買っていない行の数
    val lowCount = items.count { !it.isArchived && Stock.isLow(it) }
    val toBuy = shopping.count { !it.isChecked }

    // 期限の表示の「今日」を、アプリに戻るたびと画面を移るたびに読み直す(日をまたいでも開いた日のままにしない)。
    // 鍵の currentRoute が変わると見張りを付け直し、前面にいればすぐ ON_RESUME が届く
    LifecycleResumeEffect(currentRoute) {
        vm.refreshToday()
        onPauseOrDispose { }
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = TopLevel.entries.any { it.route == currentRoute },
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                NavigationBar {
                    TopLevel.entries.forEach { dest ->
                        val badge = when (dest) {
                            TopLevel.ITEMS -> lowCount
                            TopLevel.SHOPPING -> toBuy
                            else -> 0
                        }
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                // 同じタブの連打と、遷移の途中に届いた 2 度目のタップを捨てる
                                if (currentRoute != dest.route && backStackEntry?.isFront() == true) navController.openTab(dest.route)
                            },
                            icon = {
                                BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) {
                                    Icon(dest.icon, contentDescription = null)
                                }
                            },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // 外側で下のナビの分を空けたので、内側の Scaffold がもう一度同じ余白を足さないようにする
        AppNavHost(vm, navController, Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

/** ショートカット(FR-17)と通知から開かれたときの飛び先。 */
@Composable
private fun HandleLaunchIntent(navController: NavHostController, intent: Intent?, onHandled: () -> Unit) {
    LaunchedEffect(intent) {
        if (intent == null) return@LaunchedEffect
        val itemId = intent.getLongExtra(Routes.EXTRA_ITEM_ID, -1L)
        when {
            intent.getStringExtra(Routes.EXTRA_DESTINATION) == Routes.DEST_SCAN -> navController.navigate(Routes.SCAN)
            intent.getStringExtra(Routes.EXTRA_DESTINATION) == Routes.DEST_SHOPPING -> navController.openTab(Routes.SHOPPING)
            itemId >= 0 -> navController.navigate(Routes.detail(itemId))
        }
        onHandled()
    }
}

/** その画面がまだ前面にいるか。遷移が始まった後に届いた 2 度目のタップを捨てるために使う。 */
private fun NavBackStackEntry.isFront(): Boolean = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

private fun NavHostController.navigateOnce(from: NavBackStackEntry, route: String) {
    if (from.isFront()) navigate(route)
}

/** [from] の画面を閉じて次へ進む(スキャンの画面を戻る道に残さない)。 */
private fun NavHostController.replaceOnce(from: NavBackStackEntry, route: String) {
    if (from.isFront()) navigate(route) { popUpTo(Routes.SCAN) { inclusive = true } }
}

/** 戻る。遷移の途中の 2 度目のタップを捨て、戻り先が無いときは何もしない(戻る道を空にしない)。 */
private fun NavHostController.backOnce(from: NavBackStackEntry) {
    if (from.isFront() && previousBackStackEntry != null) popBackStack()
}

@Composable
private fun AppNavHost(vm: ShelfViewModel, navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = Routes.ITEMS,
        modifier = modifier,
        enterTransition = { fadeIn(tween(200)) },
        exitTransition = { fadeOut(tween(150)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = { fadeOut(tween(150)) },
    ) {
        composable(Routes.ITEMS) { entry ->
            ItemListScreen(
                vm,
                onOpen = { id -> navController.navigateOnce(entry, Routes.detail(id)) },
                onAdd = { navController.navigateOnce(entry, Routes.edit()) },
                onScan = { navController.navigateOnce(entry, Routes.SCAN) },
            )
        }
        composable(Routes.SHOPPING) { ShoppingScreen(vm) }
        composable(Routes.STATS) { StatsScreen(vm) }
        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(
                vm,
                onOpenCategories = { navController.navigateOnce(entry, Routes.CATEGORIES) },
                onOpenBackup = { navController.navigateOnce(entry, Routes.BACKUP) },
            )
        }
        composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: Routes.NEW_ID
            ItemDetailScreen(
                vm,
                itemId = id,
                onBack = { navController.backOnce(entry) },
                onEdit = { navController.navigateOnce(entry, Routes.edit(id)) },
            )
        }
        composable(
            Routes.EDIT,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("barcode") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            ItemEditScreen(
                vm,
                itemId = entry.arguments?.getLong("id") ?: Routes.NEW_ID,
                barcode = entry.arguments?.getString("barcode").orEmpty(),
                onDone = { navController.backOnce(entry) },
                onBack = { navController.backOnce(entry) },
            )
        }
        composable(Routes.SCAN) { entry ->
            ScanScreen(
                vm,
                onBack = { navController.backOnce(entry) },
                onFound = { id -> navController.replaceOnce(entry, Routes.detail(id)) },
                onNew = { code -> navController.replaceOnce(entry, Routes.edit(barcode = code)) },
            )
        }
        composable(Routes.CATEGORIES) { entry -> CategoryScreen(vm, onBack = { navController.backOnce(entry) }) }
        composable(Routes.BACKUP) { entry -> BackupScreen(vm, onBack = { navController.backOnce(entry) }) }
    }
}
