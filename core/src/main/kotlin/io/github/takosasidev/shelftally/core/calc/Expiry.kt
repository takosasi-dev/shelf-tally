// FR-09 期限の判定と、FR-12 定期購入の次回日。通知を出すかどうかの判断はここで決め、:app は出すだけ。
package io.github.takosasidev.shelftally.core.calc

import io.github.takosasidev.shelftally.core.model.Item
import io.github.takosasidev.shelftally.core.model.ShoppingListEntry
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class ExpiryStatus { NONE, OK, SOON, EXPIRED }

object Expiry {

    fun daysLeft(expiry: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(today, expiry)

    /** 期限の日そのものは「まだ切れていない」(SOON)。翌日から EXPIRED。 */
    fun status(expiry: LocalDate?, today: LocalDate, leadDays: Int): ExpiryStatus {
        val left = daysLeft(expiry ?: return ExpiryStatus.NONE, today)
        return when {
            left < 0 -> ExpiryStatus.EXPIRED
            left <= leadDays.coerceAtLeast(0) -> ExpiryStatus.SOON
            else -> ExpiryStatus.OK
        }
    }

    fun label(expiry: LocalDate, today: LocalDate): String {
        val left = daysLeft(expiry, today)
        return when {
            left < 0 -> "期限切れ(${-left}日経過)"
            left == 0L -> "期限は今日まで"
            else -> "期限まであと${left}日"
        }
    }

    /** 通知済みの印。期限を書き換えたら別のキーになるので、新しい期限でまた通知される。 */
    fun notifyKey(item: Item): String = "${item.id}|${item.expiryDate}"

    /**
     * 今日の見回りで通知する品目。期限が近い物と切れた物のうち、まだ通知していない物。
     * 「ちょうど N 日前の日」だけを見ると、端末の電源が切れていた日の分を逃すので、キーで 1 回に抑える。
     */
    fun toNotify(items: List<Item>, today: LocalDate, leadDays: Int, notified: Set<String>): List<Item> =
        items.filter { item ->
            !item.isArchived &&
                status(item.expiryDate, today, leadDays).let { it == ExpiryStatus.SOON || it == ExpiryStatus.EXPIRED } &&
                notifyKey(item) !in notified
        }

    /** 消えた品目や書き換えた期限のキーを捨てる(溜め続けない)。 */
    fun pruneKeys(items: List<Item>, notified: Set<String>): Set<String> {
        val alive = items.filter { it.expiryDate != null }.map(::notifyKey).toSet()
        return notified intersect alive
    }
}

object Recurring {

    /** 次に知らせる日時。間隔が未設定(null や 0 以下)なら null。起点は前回の通知、無ければ登録日時。 */
    fun nextDue(item: Item): Instant? {
        val days = item.recurringPurchaseIntervalDays?.takeIf { it > 0 } ?: return null
        return (item.lastRecurringNotifiedAt ?: item.createdAt).plus(days.toLong(), ChronoUnit.DAYS)
    }

    fun isDue(item: Item, now: Instant): Boolean =
        !item.isArchived && nextDue(item)?.let { !now.isBefore(it) } == true

    /**
     * [notify] は通知する品目。[suppressed] は時期が来たが、買い物リストに未購入で載っているので通知しない品目。
     * どちらも「今回の分は済み」として lastRecurringNotifiedAt を進める(載っている間、毎日鳴らさないため)。
     */
    data class Plan(val notify: List<Item>, val suppressed: List<Item>)

    fun plan(items: List<Item>, shopping: List<ShoppingListEntry>, now: Instant): Plan {
        val listed = shopping.filter { !it.isChecked }.mapNotNull { it.itemId }.toSet()
        val (suppressed, notify) = items.filter { isDue(it, now) }.partition { it.id in listed }
        return Plan(notify, suppressed)
    }
}
