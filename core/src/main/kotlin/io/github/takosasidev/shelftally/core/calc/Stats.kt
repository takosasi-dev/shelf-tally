// FR-10 / FR-26 履歴の集計。価格は「補充のときに払った金額」で、入っている記録だけを足す(概算)。
package io.github.takosasidev.shelftally.core.calc

import io.github.takosasidev.shelftally.core.model.ChangeType
import io.github.takosasidev.shelftally.core.model.ConsumptionLog
import io.github.takosasidev.shelftally.core.model.Item
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class CategoryCount(val categoryId: Long?, val count: Int)
data class ItemCount(val item: Item, val count: Int, val totalPrice: Int)
data class PricePoint(val date: LocalDate, val price: Int, val amount: Double)

object Stats {

    /** [from] 以降の消費の回数をカテゴリ別に。多い順。categoryId が null は未分類。 */
    fun consumptionByCategory(logs: List<ConsumptionLog>, items: List<Item>, from: Instant): List<CategoryCount> {
        val byId = items.associateBy { it.id }
        return logs.filter { it.changeType == ChangeType.CONSUME && !it.recordedAt.isBefore(from) }
            .mapNotNull { byId[it.itemId] }
            .groupingBy { it.categoryId }.eachCount()
            .map { CategoryCount(it.key, it.value) }
            .sortedWith(compareByDescending<CategoryCount> { it.count }.thenBy { it.categoryId ?: Long.MAX_VALUE })
    }

    /** その月の支出の概算。価格を入れていない補充は数えない。 */
    fun monthlySpend(logs: List<ConsumptionLog>, month: YearMonth, zone: ZoneId): Int =
        logs.filter { it.changeType == ChangeType.RESTOCK && YearMonth.from(it.recordedAt.atZone(zone)) == month }
            .sumOf { it.price ?: 0 }

    /** [last] を含む直近 [months] か月。古い月から。 */
    fun spendByMonth(logs: List<ConsumptionLog>, last: YearMonth, months: Int, zone: ZoneId): List<Pair<YearMonth, Int>> =
        (months - 1 downTo 0).map { back -> last.minusMonths(back.toLong()).let { it to monthlySpend(logs, it, zone) } }

    /** よく買う品目。補充の回数の多い順。 */
    fun topPurchased(logs: List<ConsumptionLog>, items: List<Item>, limit: Int = 5): List<ItemCount> {
        val byId = items.associateBy { it.id }
        return logs.filter { it.changeType == ChangeType.RESTOCK }
            .groupBy { it.itemId }
            .mapNotNull { (id, rows) -> byId[id]?.let { ItemCount(it, rows.size, rows.sumOf { r -> r.price ?: 0 }) } }
            .sortedWith(compareByDescending<ItemCount> { it.count }.thenBy { it.item.name })
            .take(limit)
    }

    /** 価格の推移。価格を入れた補充だけ、古い順。[logs] はこの品目の物だけ。 */
    fun priceHistory(logs: List<ConsumptionLog>, zone: ZoneId): List<PricePoint> =
        logs.filter { it.changeType == ChangeType.RESTOCK && it.price != null }
            .sortedBy { it.recordedAt }
            .map { PricePoint(it.recordedAt.atZone(zone).toLocalDate(), it.price!!, it.changeAmount) }

    /** 購入の頻度(補充の間隔の平均、日)。補充が 2 回未満なら null。[logs] はこの品目の物だけ。 */
    fun averagePurchaseIntervalDays(logs: List<ConsumptionLog>): Double? {
        val times = logs.filter { it.changeType == ChangeType.RESTOCK }.map { it.recordedAt }.sorted()
        if (times.size < 2) return null
        return ChronoUnit.HOURS.between(times.first(), times.last()) / 24.0 / (times.size - 1)
    }
}
