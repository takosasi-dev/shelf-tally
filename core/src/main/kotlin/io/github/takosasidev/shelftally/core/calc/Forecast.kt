// FR-11 残量の予測(ルールベース)。直近の消費の間隔の平均から「あと約何日」を出す。
package io.github.takosasidev.shelftally.core.calc

import io.github.takosasidev.shelftally.core.model.ChangeType
import io.github.takosasidev.shelftally.core.model.ConsumptionLog
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

sealed interface ForecastResult {
    /** 履歴が足りない。断定的な日数は出さない。 */
    data object Insufficient : ForecastResult
    data class Days(val days: Double) : ForecastResult
}

object Forecast {

    /** 予測に要る「消費した日」の数。3 日 = 間隔 2 つ。1 つの間隔だけでは偶然と区別できない。 */
    const val MIN_CONSUME_DAYS = 3

    /** 見る範囲。直近 4 日 = 間隔 3 つ(要件定義書 §8 の「直近3回の消費間隔」)。 */
    const val WINDOW_CONSUME_DAYS = 4

    const val TEXT_INSUFFICIENT = "予測に必要なデータが不足しています"

    /**
     * [logs] はこの品目の物だけ。
     * 同じ日の消費は 1 回にまとめる(−を続けて押した分を「間隔 0 日」と数えないため)。
     * 速さ = 最初の日より後に使った量 ÷ 最初の日から最後の日までの日数。残り日数 = 残量 ÷ 速さ。
     */
    fun remainingDays(currentQuantity: Double, logs: List<ConsumptionLog>, zone: ZoneId): ForecastResult {
        val perDay = logs.filter { it.changeType == ChangeType.CONSUME && it.changeAmount > 0.0 }
            .groupBy { it.recordedAt.atZone(zone).toLocalDate() }
            .mapValues { (_, v) -> v.sumOf { it.changeAmount } }
            .toSortedMap()
            .entries.toList()
            .takeLast(WINDOW_CONSUME_DAYS)
        if (perDay.size < MIN_CONSUME_DAYS) return ForecastResult.Insufficient

        val spanDays = ChronoUnit.DAYS.between(perDay.first().key, perDay.last().key)
        val used = perDay.drop(1).sumOf { it.value }
        if (spanDays <= 0 || used <= 0.0) return ForecastResult.Insufficient
        return ForecastResult.Days(currentQuantity.coerceAtLeast(0.0) / (used / spanDays))
    }

    fun label(result: ForecastResult, currentQuantity: Double): String = when {
        currentQuantity <= 0.0 -> "在庫切れ"
        result !is ForecastResult.Days -> TEXT_INSUFFICIENT
        result.days < 1.0 -> "あと1日以内になくなる見込み"
        else -> "あと約${result.days.roundToInt()}日でなくなる見込み"
    }
}
