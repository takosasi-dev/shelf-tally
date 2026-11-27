// 残量の判定。低在庫・在庫切れ・満タンの見積もり・1 タップの増減幅。
package io.github.takosasidev.shelftally.core.calc

import io.github.takosasidev.shelftally.core.model.ChangeType
import io.github.takosasidev.shelftally.core.model.ConsumptionLog
import io.github.takosasidev.shelftally.core.model.Item
import java.math.BigDecimal
import java.math.RoundingMode

enum class StockLevel { OUT, LOW, OK }

object Stock {

    /** 量り売りの単位。1 タップの幅を決められないので、増減のたびに量を入力させる。 */
    private val MEASURED_UNITS = setOf("g", "kg", "mg", "ml", "l", "dl", "cc")

    /** FR-03 の条件そのまま(残量が閾値以下)。 */
    fun isLow(item: Item): Boolean = item.currentQuantity <= item.lowStockThreshold

    fun level(item: Item): StockLevel = when {
        item.currentQuantity <= 0.0 -> StockLevel.OUT
        isLow(item) -> StockLevel.LOW
        else -> StockLevel.OK
    }

    /**
     * 「低在庫でない → 低在庫」に変わったか。通知と買い物リストへの自動追加の合図。
     * 状態の列を持たずに「下回った時に 1 回だけ、上回ってからまた下回れば再び」を満たすため、変わり目だけを見る。
     * [before] が null は新規登録。アーカイブ中の品目は対象外。
     */
    fun crossedIntoLow(before: Item?, after: Item): Boolean {
        if (after.isArchived || !isLow(after)) return false
        return before == null || before.isArchived || !isLow(before)
    }

    /** 1 タップの増減幅。個数の単位は 1、量り売りの単位は null(入力させる)。 */
    fun defaultStep(unit: String): Double? =
        if (unit.trim().lowercase() in MEASURED_UNITS) null else 1.0

    /** 実際に動く量。消費は残量までで止める(0 未満にしない)。 */
    fun effectiveAmount(current: Double, type: ChangeType, amount: Double): Double {
        val a = amount.coerceAtLeast(0.0)
        return if (type == ChangeType.CONSUME) minOf(a, current.coerceAtLeast(0.0)) else a
    }

    fun applyChange(current: Double, type: ChangeType, amount: Double): Double {
        val a = effectiveAmount(current, type, amount)
        // 0.1 + 0.2 のような端数を残量に溜めない
        return round3(if (type == ChangeType.CONSUME) current - a else current + a).coerceAtLeast(0.0)
    }

    /** 履歴をさかのぼって、これまでの残量の最大を出す。[logs] はこの品目の物だけ。 */
    fun peakQuantity(current: Double, logs: List<ConsumptionLog>): Double {
        var level = current
        var peak = current
        logs.sortedByDescending { it.recordedAt }.forEach { log ->
            level = if (log.changeType == ChangeType.RESTOCK) level - log.changeAmount else level + log.changeAmount
            level = level.coerceAtLeast(0.0)
            peak = maxOf(peak, level)
        }
        return peak
    }

    /**
     * 「満タン」の見積もり(要件定義書 §7)。最大量の列は無いので、これまでの最大の残量を使う。
     * 閾値を超えたことが 1 度も無い品目(少ないまま登録した物)は、最大の残量が満タンとは言えないので、閾値の 3 倍で代える。
     * 大きい方を取ると、3 個で登録した品目の満タンが「持ったことのない 6 個」になり、割合の意味が通らない。
     * どちらも 0 のときは 1(残量バーの割り算のため)。
     */
    fun fullAmount(item: Item, logs: List<ConsumptionLog>): Double {
        val peak = peakQuantity(item.currentQuantity, logs)
        val full = if (peak > item.lowStockThreshold) peak else item.lowStockThreshold * 3
        return if (full > 0.0) full else 1.0
    }

    /** 残量バーの長さ(0〜1)。 */
    fun ratio(item: Item, logs: List<ConsumptionLog>): Float =
        (item.currentQuantity / fullAmount(item, logs)).coerceIn(0.0, 1.0).toFloat()

    /**
     * 一覧と詳細に出す文。「在庫切れ」「残り 3 個(満タン)」「残り 2 個(満タン 3 個の 67%)」。
     * 割合だけを書くと何に対する割合か分からないので、分母(満タンの見積もり)も書く。
     */
    fun label(item: Item, logs: List<ConsumptionLog>): String {
        if (level(item) == StockLevel.OUT) return "在庫切れ"
        val left = "残り ${Format.quantity(item.currentQuantity, item.unit)}"
        val full = fullAmount(item, logs)
        if (item.currentQuantity >= full) return "$left(満タン)"
        // 満タンでも在庫切れでもないのに「100%」「0%」と丸めて書かない
        val percent = Math.round(ratio(item, logs) * 100).coerceIn(1, 99)
        return "$left(満タン ${Format.quantity(full, item.unit)}の $percent%)"
    }

    /** 「購入完了」で提案する補充の量。満タンまでの差。差が無ければ 1 タップの幅、それも無ければ満タンの量。 */
    fun suggestedRestock(item: Item, logs: List<ConsumptionLog>): Double {
        val full = fullAmount(item, logs)
        val gap = round3(full - item.currentQuantity)
        return if (gap > 0.0) gap else defaultStep(item.unit) ?: full
    }

    private fun round3(v: Double): Double = BigDecimal(v).setScale(3, RoundingMode.HALF_UP).toDouble()
}

object Format {
    /** 2.0 → "2"、1.50 → "1.5"。小数は 3 桁まで。 */
    fun quantity(v: Double): String =
        BigDecimal(v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    fun quantity(v: Double, unit: String): String = "${quantity(v)} $unit".trim()

    /** 入力欄の文字を数に。全角の数字と「,」を許す。0 未満や数でない物は null。 */
    fun parseQuantity(raw: String): Double? {
        val s = raw.trim().replace(",", "").map { c -> if (c in '０'..'９') '0' + (c - '０') else if (c == '．') '.' else c }
            .joinToString("")
        return s.toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() }
    }

    fun yen(v: Int): String = "%,d円".format(v)
}
