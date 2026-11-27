package io.github.takosasidev.shelftally.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.takosasidev.shelftally.core.calc.ItemFilter
import io.github.takosasidev.shelftally.core.calc.ItemQuery
import io.github.takosasidev.shelftally.core.model.AppSettings
import io.github.takosasidev.shelftally.core.model.Category
import io.github.takosasidev.shelftally.core.model.ConsumptionLog
import io.github.takosasidev.shelftally.core.model.Item
import io.github.takosasidev.shelftally.core.model.ShoppingListEntry
import io.github.takosasidev.shelftally.core.model.ViewMode
import io.github.takosasidev.shelftally.data.ShelfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 全画面で 1 つを共有する(MainActivity が持つ)。家庭の消耗品の量なら、全件を StateFlow に載せて
 * 各画面が要る物を拾うほうが、画面ごとに問い合わせを書くより単純。判定は :core の関数に任せる。
 */
class ShelfViewModel(val repo: ShelfRepository) : ViewModel() {

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val items: StateFlow<List<Item>> = repo.items.state(emptyList())
    val categories: StateFlow<List<Category>> = repo.categories.state(emptyList())
    val shopping: StateFlow<List<ShoppingListEntry>> = repo.shopping.state(emptyList())
    val logs: StateFlow<List<ConsumptionLog>> = repo.logs.state(emptyList())

    /** 品目ごとの履歴(新しい順)。残量バーと予測が使う。 */
    val logsByItem: StateFlow<Map<Long, List<ConsumptionLog>>> =
        repo.logs.map { all -> all.groupBy { it.itemId } }.flowOn(Dispatchers.Default).state(emptyMap())

    /** null は「まだ読めていない」。アプリロックの有無が分かるまで、中身を出さないために区別する。 */
    val settings: StateFlow<AppSettings?> = repo.settings.map<AppSettings, AppSettings?> { it }.state(null)

    /** 一覧の検索・絞り込み・並び替え(FR-14)。 */
    val query = MutableStateFlow(ItemQuery())

    /** 一覧の表示の形。null の間は設定の既定に従う(FR-23)。 */
    val viewMode = MutableStateFlow<ViewMode?>(null)

    /**
     * 期限の表示と絞り込みに使う「今日」。開いた時点の日付のままにすると、日をまたいで開きっぱなしにしたとき
     * 期限の残り日数がずれるので、画面に戻るたびに [refreshToday] で読み直す(同じ日なら何も起きない)。
     */
    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today

    fun refreshToday() {
        _today.value = LocalDate.now()
    }

    val visibleItems: StateFlow<List<Item>> =
        combine(repo.items, query, repo.settings, _today) { all, q, s, day ->
            ItemFilter.apply(all, q, day, s.expiryAlertLeadDays)
        }.flowOn(Dispatchers.Default).state(emptyList())

    /** 結果を待たない書き込み。画面は `vm.act { changeQuantity(...) }` のように呼ぶ。 */
    fun act(block: suspend ShelfRepository.() -> Unit) {
        viewModelScope.launch { repo.block() }
    }

    companion object {
        fun factory(context: Context) = viewModelFactory {
            initializer { ShelfViewModel(ShelfRepository.get(context.applicationContext)) }
        }
    }
}
