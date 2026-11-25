package io.github.takosasidev.shelftally.core

import io.github.takosasidev.shelftally.core.calc.Format
import io.github.takosasidev.shelftally.core.calc.Stock
import io.github.takosasidev.shelftally.core.calc.StockLevel
import io.github.takosasidev.shelftally.core.model.ChangeType
import io.github.takosasidev.shelftally.core.model.ConsumptionLog
import io.github.takosasidev.shelftally.core.model.Item
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val T0: Instant = Instant.parse("2026-11-01T03:00:00Z")

internal fun item(
    id: Long = 1,
    qty: Double = 5.0,
    threshold: Double = 2.0,
    name: String = "洗剤",
    unit: String = "個",
    archived: Boolean = false,
) = Item(id = id, name = name, currentQuantity = qty, unit = unit, lowStockThreshold = threshold, isArchived = archived, createdAt = T0)

internal fun log(type: ChangeType, amount: Double, day: Long, itemId: Long = 1, price: Int? = null, id: Long = 0) =
    ConsumptionLog(id = id, itemId = itemId, changeType = type, changeAmount = amount, price = price, recordedAt = T0.plusSeconds(day * 86_400))

class StockTest {

    @Test
    fun `残量が閾値と同じなら低在庫、1つ多ければ低在庫でない`() {
        assertTrue(Stock.isLow(item(qty = 2.0, threshold = 2.0)))
        assertFalse(Stock.isLow(item(qty = 2.001, threshold = 2.0)))
        assertEquals(StockLevel.LOW, Stock.level(item(qty = 2.0, threshold = 2.0)))
        assertEquals(StockLevel.OK, Stock.level(item(qty = 3.0, threshold = 2.0)))
    }

    @Test
    fun `残量0は閾値に関わらず在庫切れ`() {
        assertEquals(StockLevel.OUT, Stock.level(item(qty = 0.0, threshold = 0.0)))
        assertEquals(StockLevel.OUT, Stock.level(item(qty = 0.0, threshold = 5.0)))
        assertEquals(StockLevel.OK, Stock.level(item(qty = 1.0, threshold = 0.0)))
    }

    @Test
    fun `閾値をまたいだ時だけ合図が出る`() {
        val ok = item(qty = 3.0)
        val low = item(qty = 2.0)
        assertTrue(Stock.crossedIntoLow(ok, low))
        // 下回ったままさらに減っても、もう一度は出ない
        assertFalse(Stock.crossedIntoLow(low, item(qty = 1.0)))
        // 上回ってからまた下回れば出る
        assertFalse(Stock.crossedIntoLow(low, ok))
        assertTrue(Stock.crossedIntoLow(ok, item(qty = 0.0)))
    }

    @Test
    fun `新規登録で最初から少ない品目と、閾値を上げて少なくなった品目も合図が出る`() {
        assertTrue(Stock.crossedIntoLow(null, item(qty = 1.0, threshold = 2.0)))
        assertFalse(Stock.crossedIntoLow(null, item(qty = 5.0, threshold = 2.0)))
        assertTrue(Stock.crossedIntoLow(item(qty = 5.0, threshold = 2.0), item(qty = 5.0, threshold = 5.0)))
    }

    @Test
    fun `アーカイブ中の品目は合図を出さない。戻した時に少なければ出す`() {
        assertFalse(Stock.crossedIntoLow(item(qty = 3.0), item(qty = 1.0, archived = true)))
        assertTrue(Stock.crossedIntoLow(item(qty = 1.0, archived = true), item(qty = 1.0)))
    }

    @Test
    fun `個数の単位は1ずつ、量り売りの単位は幅を決めない`() {
        assertEquals(1.0, Stock.defaultStep("個"))
        assertEquals(1.0, Stock.defaultStep("ロール"))
        assertNull(Stock.defaultStep("g"))
        assertNull(Stock.defaultStep(" mL "))
        assertNull(Stock.defaultStep("L"))
    }

    @Test
    fun `消費は0で止まり、実際に動いた量は残量まで`() {
        assertEquals(0.0, Stock.applyChange(2.0, ChangeType.CONSUME, 5.0))
        assertEquals(2.0, Stock.effectiveAmount(2.0, ChangeType.CONSUME, 5.0))
        assertEquals(7.0, Stock.applyChange(2.0, ChangeType.RESTOCK, 5.0))
        assertEquals(2.0, Stock.applyChange(2.0, ChangeType.CONSUME, -3.0))
    }

    @Test
    fun `小数の足し引きで端数が残らない`() {
        assertEquals(0.3, Stock.applyChange(0.1, ChangeType.RESTOCK, 0.2))
        assertEquals(0.0, Stock.applyChange(0.3, ChangeType.CONSUME, 0.3))
    }

    @Test
    fun `履歴をさかのぼって最大の残量を出す`() {
        // 12 から 3 回使って 9、4 足して 13、今は 2 回使って 11
        val logs = listOf(
            log(ChangeType.CONSUME, 3.0, 1),
            log(ChangeType.RESTOCK, 4.0, 2),
            log(ChangeType.CONSUME, 2.0, 3),
        )
        assertEquals(13.0, Stock.peakQuantity(11.0, logs))
        assertEquals(11.0, Stock.peakQuantity(11.0, emptyList()))
    }

    @Test
    fun `満タンは最大の残量と閾値の3倍の大きい方、どちらも0なら1`() {
        assertEquals(6.0, Stock.fullAmount(item(qty = 1.0, threshold = 2.0), emptyList()))
        assertEquals(10.0, Stock.fullAmount(item(qty = 10.0, threshold = 2.0), emptyList()))
        assertEquals(1.0, Stock.fullAmount(item(qty = 0.0, threshold = 0.0), emptyList()))
    }

    @Test
    fun `残量バーは0から1に収まる`() {
        assertEquals(0.5f, Stock.ratio(item(qty = 3.0, threshold = 2.0), emptyList()))
        assertEquals(0f, Stock.ratio(item(qty = 0.0, threshold = 0.0), emptyList()))
        assertEquals(1f, Stock.ratio(item(qty = 9.0, threshold = 2.0), emptyList()))
    }

    @Test
    fun `購入完了の提案は満タンまでの差。差が無ければ1タップの幅`() {
        assertEquals(5.0, Stock.suggestedRestock(item(qty = 1.0, threshold = 2.0), emptyList()))
        assertEquals(1.0, Stock.suggestedRestock(item(qty = 6.0, threshold = 2.0), emptyList()))
        // 量り売りは幅が無いので満タンの量
        assertEquals(600.0, Stock.suggestedRestock(item(qty = 600.0, threshold = 200.0, unit = "g"), emptyList()))
    }

    @Test
    fun `数量の表示は余分な0を出さない`() {
        assertEquals("2", Format.quantity(2.0))
        assertEquals("1.5", Format.quantity(1.50))
        assertEquals("100", Format.quantity(100.0))
        assertEquals("0", Format.quantity(0.0))
        assertEquals("2 個", Format.quantity(2.0, "個"))
    }

    @Test
    fun `数量の入力は全角とカンマを受け、負の数と文字を弾く`() {
        assertEquals(1200.0, Format.parseQuantity("1,200"))
        assertEquals(12.5, Format.parseQuantity("１２．５"))
        assertNull(Format.parseQuantity("-1"))
        assertNull(Format.parseQuantity("abc"))
        assertNull(Format.parseQuantity(""))
    }
}
