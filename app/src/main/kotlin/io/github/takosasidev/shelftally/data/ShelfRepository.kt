package io.github.takosasidev.shelftally.data

import android.content.Context
import androidx.room.withTransaction
import io.github.takosasidev.shelftally.core.backup.BackupContent
import io.github.takosasidev.shelftally.core.backup.BackupFormat
import io.github.takosasidev.shelftally.core.calc.ShoppingList
import io.github.takosasidev.shelftally.core.calc.Stock
import io.github.takosasidev.shelftally.core.model.AppSettings
import io.github.takosasidev.shelftally.core.model.Category
import io.github.takosasidev.shelftally.core.model.ChangeType
import io.github.takosasidev.shelftally.core.model.ConsumptionLog
import io.github.takosasidev.shelftally.core.model.Item
import io.github.takosasidev.shelftally.core.model.ShoppingListEntry
import io.github.takosasidev.shelftally.data.db.AppDatabase
import io.github.takosasidev.shelftally.data.db.CategoryEntity
import io.github.takosasidev.shelftally.data.db.toEntity
import io.github.takosasidev.shelftally.data.db.toModel
import io.github.takosasidev.shelftally.notify.Notifier
import io.github.takosasidev.shelftally.widget.LowStockWidget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** 購入完了で在庫に足す 1 件分。 */
data class Purchase(val itemId: Long, val amount: Double, val price: Int?)

/**
 * 画面・通知・ウィジェット・バックアップが触る唯一のデータの入口。
 * 判定は :core の関数に任せ、ここは読み書きと「残量が変わった後にすること」だけを持つ。
 */
class ShelfRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val itemDao = db.itemDao()
    private val categoryDao = db.categoryDao()
    private val logDao = db.consumptionLogDao()
    private val shoppingDao = db.shoppingListDao()
    private val settingsDao = db.appSettingsDao()

    val items: Flow<List<Item>> = itemDao.observeAll().map { l -> l.map { it.toModel() } }
    val categories: Flow<List<Category>> = categoryDao.observeAll().map { l -> l.map { it.toModel() } }
    val logs: Flow<List<ConsumptionLog>> = logDao.observeAll().map { l -> l.map { it.toModel() } }
    val shopping: Flow<List<ShoppingListEntry>> = shoppingDao.observeAll().map { l -> l.map { it.toModel() } }
    val settings: Flow<AppSettings> = settingsDao.observe().map { it?.toModel() ?: AppSettings() }

    suspend fun itemsOnce(): List<Item> = itemDao.getAll().map { it.toModel() }
    suspend fun shoppingOnce(): List<ShoppingListEntry> = shoppingDao.getAll().map { it.toModel() }
    suspend fun settingsOnce(): AppSettings = settingsDao.get()?.toModel() ?: AppSettings()

    /** 初めての起動で、設定の 1 行と、例のカテゴリを入れる。設定の行があれば何もしない。 */
    suspend fun ensureDefaults() = db.withTransaction {
        if (settingsDao.get() == null) {
            settingsDao.upsert(AppSettings().toEntity())
            if (categoryDao.getAll().isEmpty()) categoryDao.insertAll(DEFAULT_CATEGORIES)
        }
    }

    // ---- 品目 --------------------------------------------------------------

    /** 新規(id = 0)なら足し、そうでなければ書き換える。戻り値は品目の id。 */
    suspend fun saveItem(item: Item): Long {
        val before = if (item.id == 0L) null else itemDao.getById(item.id)?.toModel()
        val id = if (before == null) {
            itemDao.insert(item.copy(id = 0L).toEntity())
        } else {
            itemDao.update(item.toEntity())
            item.id
        }
        afterChange(before, item.copy(id = id))
        if (before?.photoUri != null && before.photoUri != item.photoUri) prunePhotos()
        return id
    }

    /**
     * FR-02 / FR-10: 残量を増減し、履歴に 1 行残す。消費は 0 で止まり、履歴には実際に動いた量を書く。
     * 動かない操作(残量 0 からの消費など)は履歴も残さない。
     */
    suspend fun changeQuantity(itemId: Long, type: ChangeType, amount: Double, price: Int? = null) {
        val (before, after) = db.withTransaction {
            val before = itemDao.getById(itemId)?.toModel() ?: return@withTransaction null
            val moved = Stock.effectiveAmount(before.currentQuantity, type, amount)
            if (moved <= 0.0) return@withTransaction null
            val after = before.copy(currentQuantity = Stock.applyChange(before.currentQuantity, type, amount))
            itemDao.update(after.toEntity())
            logDao.insert(
                ConsumptionLog(
                    itemId = itemId,
                    changeType = type,
                    changeAmount = moved,
                    price = price?.takeIf { type == ChangeType.RESTOCK && it >= 0 },
                    recordedAt = Instant.now(),
                ).toEntity(),
            )
            before to after
        } ?: return
        afterChange(before, after)
    }

    /** FR-27: 品目を消す。履歴と買い物リストの行は外部キーで一緒に消える。 */
    suspend fun deleteItem(id: Long) {
        itemDao.deleteById(id)
        Notifier.cancel(appContext, id)
        prunePhotos()
        LowStockWidget.refresh(appContext)
    }

    suspend fun setArchived(id: Long, archived: Boolean) {
        val item = itemDao.getById(id)?.toModel() ?: return
        saveItem(item.copy(isArchived = archived))
    }

    /** FR-12: 定期購入の「今回の分は済み」の印を進める。 */
    suspend fun markRecurringHandled(ids: Collection<Long>, at: Instant) = db.withTransaction {
        ids.forEach { id ->
            itemDao.getById(id)?.let { itemDao.update(it.copy(lastRecurringNotifiedAt = at)) }
        }
    }

    /**
     * 残量や閾値が変わった後にすること。
     * 低在庫に変わった瞬間だけ、買い物リストへの自動追加(FR-04)と通知(FR-03)をする。
     * 在庫が戻ったら、自動で足した未購入の行を片付ける。最後にウィジェットを描き直す。
     */
    private suspend fun afterChange(before: Item?, after: Item) {
        if (Stock.crossedIntoLow(before, after)) {
            ShoppingList.entryFor(after, shoppingOnce(), auto = true, now = Instant.now())
                ?.let { shoppingDao.insert(it.toEntity()) }
            Notifier.lowStock(appContext, after)
        } else if (before != null && Stock.isLow(before) && !Stock.isLow(after)) {
            shoppingDao.deleteOpenAutoFor(after.id)
        }
        LowStockWidget.refresh(appContext)
    }

    private suspend fun prunePhotos() {
        PhotoStore.pruneOrphans(appContext, itemDao.getAll().mapNotNull { it.photoUri }.toSet())
    }

    // ---- カテゴリ ----------------------------------------------------------

    suspend fun saveCategory(category: Category) {
        if (category.id == 0L) {
            val next = (categoryDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
            categoryDao.insert(category.copy(sortOrder = next).toEntity())
        } else {
            categoryDao.update(category.toEntity())
        }
    }

    /** カテゴリを消す。属していた品目は外部キー(SET_NULL)で未分類に移る。 */
    suspend fun deleteCategory(id: Long) = categoryDao.deleteById(id)

    /** 並びを 1 つ上か下へ動かし、sortOrder を 0 から振り直す。 */
    suspend fun moveCategory(id: Long, up: Boolean) = db.withTransaction {
        val list = categoryDao.getAll().toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = if (up) from - 1 else from + 1
        if (from < 0 || to !in list.indices) return@withTransaction
        list.add(to, list.removeAt(from))
        categoryDao.updateAll(list.mapIndexed { index, c -> c.copy(sortOrder = index) })
    }

    // ---- 買い物リスト ------------------------------------------------------

    /** 在庫の品目を手動で足す。既に同じ品目の行があれば false(重複させない)。 */
    suspend fun addItemToShopping(item: Item): Boolean {
        val entry = ShoppingList.entryFor(item, shoppingOnce(), auto = false, now = Instant.now()) ?: return false
        shoppingDao.insert(entry.toEntity())
        return true
    }

    suspend fun addFreeEntry(label: String): Boolean {
        val entry = ShoppingList.freeEntry(label, Instant.now()) ?: return false
        shoppingDao.insert(entry.toEntity())
        return true
    }

    suspend fun setChecked(id: Long, checked: Boolean) = shoppingDao.setChecked(id, checked)

    suspend fun deleteEntry(id: Long) = shoppingDao.deleteById(id)

    /** FR-05: 購入完了。在庫に足し、チェック済みの行を消す。 */
    suspend fun completePurchase(purchases: List<Purchase>) {
        purchases.forEach { changeQuantity(it.itemId, ChangeType.RESTOCK, it.amount, it.price) }
        shoppingDao.deleteChecked()
    }

    // ---- 設定 --------------------------------------------------------------

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = db.withTransaction {
        settingsDao.upsert(transform(settingsOnce()).toEntity())
    }

    // ---- バックアップ ------------------------------------------------------

    suspend fun snapshot(): BackupContent = db.withTransaction {
        BackupContent(
            categories = categoryDao.getAll().map { it.toModel() },
            items = itemsOnce(),
            logs = logDao.getAll().map { it.toModel() },
            shopping = shoppingOnce(),
            settings = settingsOnce(),
        )
    }

    /**
     * FR-18: 今のデータを全部消して、バックアップの中身に入れ替える。
     * 途中で失敗したら全部が元に戻る(1 つのトランザクション)。
     * 最終の自動バックアップの日時は端末ごとの物なので、今の値を保つ。
     */
    suspend fun replaceAll(content: BackupContent) {
        val clean = BackupFormat.sanitize(content)
        db.withTransaction {
            val lastAuto = settingsOnce().lastAutoBackupAt
            // 外部キーの子から先に消し、親から先に入れる
            shoppingDao.deleteAll()
            logDao.deleteAll()
            itemDao.deleteAll()
            categoryDao.deleteAll()
            categoryDao.insertAll(clean.categories.map { it.toEntity() })
            itemDao.insertAll(clean.items.map { it.toEntity() })
            logDao.insertAll(clean.logs.map { it.toEntity() })
            shoppingDao.insertAll(clean.shopping.map { it.toEntity() })
            settingsDao.upsert(clean.settings.copy(lastAutoBackupAt = lastAuto).toEntity())
        }
        // 前のデータの通知を残すと、タップで同じ id の別の品目が開く
        Notifier.cancel(appContext, null)
        prunePhotos()
        LowStockWidget.refresh(appContext)
    }

    companion object {
        /** 初めての起動で入れる例(要件定義書 §4 の例)。名前も色も後から変えられる。 */
        private val DEFAULT_CATEGORIES = listOf(
            CategoryEntity(name = "日用品", colorArgb = 0xFF3F51B5.toInt(), sortOrder = 0),
            CategoryEntity(name = "食品", colorArgb = 0xFFE07A2F.toInt(), sortOrder = 1),
            CategoryEntity(name = "ペット用品", colorArgb = 0xFF2E7D32.toInt(), sortOrder = 2),
            CategoryEntity(name = "医薬品", colorArgb = 0xFFB3325B.toInt(), sortOrder = 3),
        )

        @Volatile
        private var instance: ShelfRepository? = null

        fun get(context: Context): ShelfRepository = instance ?: synchronized(this) {
            instance ?: ShelfRepository(context).also { instance = it }
        }
    }
}
