// FR-14 検索・絞り込み・並び替え。FR-08 の保管場所の候補もここで作る。
package io.github.takosasidev.shelftally.core.calc

import io.github.takosasidev.shelftally.core.model.Item
import java.time.LocalDate

enum class ItemSort(val label: String) { NAME("名前順"), QUANTITY("残量が少ない順"), EXPIRY("期限が近い順") }
enum class LevelFilter(val label: String) { ALL("すべて"), LOW("少ない"), OUT("在庫切れ") }

data class ItemQuery(
    val text: String = "",
    /** null は全カテゴリ。[ItemFilter.UNCATEGORIZED] は未分類だけ。 */
    val categoryId: Long? = null,
    val location: String? = null,
    val level: LevelFilter = LevelFilter.ALL,
    val expiringOnly: Boolean = false,
    /** true ならアーカイブした品目だけを出す(普段の一覧には出さない)。 */
    val archived: Boolean = false,
    val sort: ItemSort = ItemSort.NAME,
)

object ItemFilter {

    const val UNCATEGORIZED = -1L

    fun apply(items: List<Item>, q: ItemQuery, today: LocalDate, leadDays: Int): List<Item> {
        val needle = q.text.trim().lowercase()
        val filtered = items.filter { item ->
            item.isArchived == q.archived &&
                (needle.isEmpty() || item.name.lowercase().contains(needle) || item.barcodeValue == needle) &&
                when (q.categoryId) {
                    null -> true
                    UNCATEGORIZED -> item.categoryId == null
                    else -> item.categoryId == q.categoryId
                } &&
                (q.location == null || item.locationTag == q.location) &&
                when (q.level) {
                    LevelFilter.ALL -> true
                    LevelFilter.LOW -> Stock.isLow(item)
                    LevelFilter.OUT -> Stock.level(item) == StockLevel.OUT
                } &&
                (!q.expiringOnly || Expiry.status(item.expiryDate, today, leadDays)
                    .let { it == ExpiryStatus.SOON || it == ExpiryStatus.EXPIRED })
        }
        return when (q.sort) {
            ItemSort.NAME -> filtered.sortedBy { it.name }
            // 単位が違う品目の数は比べられないので、まず段階(切れ → 少ない → 十分)、次に数
            ItemSort.QUANTITY -> filtered.sortedWith(
                compareBy<Item> { Stock.level(it).ordinal }.thenBy { it.currentQuantity }.thenBy { it.name },
            )
            // 期限の無い品目は最後
            ItemSort.EXPIRY -> filtered.sortedWith(
                compareBy<Item> { it.expiryDate == null }.thenBy { it.expiryDate }.thenBy { it.name },
            )
        }
    }

    /** 保管場所の候補。よく使う順。 */
    fun locations(items: List<Item>): List<String> =
        items.mapNotNull { it.locationTag?.trim()?.takeIf(String::isNotEmpty) }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
}
