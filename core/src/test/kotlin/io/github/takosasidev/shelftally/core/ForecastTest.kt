package io.github.takosasidev.shelftally.core

import io.github.takosasidev.shelftally.core.calc.Forecast
import io.github.takosasidev.shelftally.core.calc.ForecastResult
import io.github.takosasidev.shelftally.core.model.ChangeType
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ForecastTest {

    private val zone = ZoneOffset.UTC

    /** 要件定義書 §8: 直近 3 回の消費間隔が平均 5 日 → 「あと約5日でなくなる見込み」。 */
    @Test
    fun `直近3回の間隔が平均5日で、1回に1つ使い、残りが1つなら約5日`() {
        val logs = listOf(0L, 4L, 10L, 15L).map { log(ChangeType.CONSUME, 1.0, it) } // 間隔 4, 6, 5
        val result = Forecast.remainingDays(1.0, logs, zone)
        assertIs<ForecastResult.Days>(result)
        assertEquals(5.0, result.days, 1e-9)
        assertEquals("あと約5日でなくなる見込み", Forecast.label(result, 1.0))
    }

    @Test
    fun `残りが多ければ日数も比例して延びる`() {
        val logs = listOf(0L, 5L, 10L, 15L).map { log(ChangeType.CONSUME, 1.0, it) }
        assertEquals(15.0, (Forecast.remainingDays(3.0, logs, zone) as ForecastResult.Days).days, 1e-9)
    }

    @Test
    fun `見るのは直近4日分だけ。古い速い消費は効かない`() {
        val old = listOf(-30L, -29L, -28L).map { log(ChangeType.CONSUME, 1.0, it) }
        val recent = listOf(0L, 10L, 20L, 30L).map { log(ChangeType.CONSUME, 1.0, it) }
        assertEquals(10.0, (Forecast.remainingDays(1.0, old + recent, zone) as ForecastResult.Days).days, 1e-9)
    }

    @Test
    fun `消費した日が2日以下ならデータ不足`() {
        assertEquals(ForecastResult.Insufficient, Forecast.remainingDays(5.0, emptyList(), zone))
        assertEquals(ForecastResult.Insufficient, Forecast.remainingDays(5.0, listOf(log(ChangeType.CONSUME, 1.0, 0)), zone))
        val two = listOf(0L, 5L).map { log(ChangeType.CONSUME, 1.0, it) }
        assertEquals(ForecastResult.Insufficient, Forecast.remainingDays(5.0, two, zone))
        assertEquals(Forecast.TEXT_INSUFFICIENT, Forecast.label(ForecastResult.Insufficient, 5.0))
    }

    @Test
    fun `同じ日に続けて押した分は1回に数える`() {
        // 1 日に 3 回押しただけでは「消費した日」は 1 日
        val burst = List(3) { log(ChangeType.CONSUME, 1.0, 0) }
        assertEquals(ForecastResult.Insufficient, Forecast.remainingDays(5.0, burst, zone))
        // 3 日とも 2 つずつ使う(同じ日に 1 つを 2 回)→ 最初の日の後、4 日で 4 つ = 1 日 1 つ
        val logs = listOf(0L, 0L, 2L, 2L, 4L, 4L).map { log(ChangeType.CONSUME, 1.0, it) }
        assertEquals(6.0, (Forecast.remainingDays(6.0, logs, zone) as ForecastResult.Days).days, 1e-9)
    }

    @Test
    fun `補充の記録は数えない`() {
        val logs = listOf(0L, 5L, 10L).map { log(ChangeType.RESTOCK, 1.0, it) }
        assertEquals(ForecastResult.Insufficient, Forecast.remainingDays(5.0, logs, zone))
    }

    @Test
    fun `残量0は在庫切れ、1日未満は1日以内と書く`() {
        val logs = listOf(0L, 1L, 2L).map { log(ChangeType.CONSUME, 4.0, it) }
        assertEquals("在庫切れ", Forecast.label(Forecast.remainingDays(0.0, logs, zone), 0.0))
        assertEquals("あと1日以内になくなる見込み", Forecast.label(Forecast.remainingDays(1.0, logs, zone), 1.0))
    }
}
