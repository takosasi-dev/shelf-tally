package io.github.takosasidev.shelftally.notify

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.takosasidev.shelftally.MainActivity
import io.github.takosasidev.shelftally.R
import io.github.takosasidev.shelftally.core.calc.Expiry
import io.github.takosasidev.shelftally.core.calc.Format
import io.github.takosasidev.shelftally.core.model.Item
import io.github.takosasidev.shelftally.ui.nav.Routes
import java.time.LocalDate

/**
 * ローカル通知だけ(INTERNET の権限が無いので、ほかの手段は無い)。
 * どの関数も、出せたら true、権限が無い・切られているなら静かに false を返す。
 * 呼ぶ側は true のときだけ「通知済み」にする(権限をもらう前の分を潰さないため)。
 */
object Notifier {

    private const val CHANNEL_LOW = "low_stock"
    private const val CHANNEL_EXPIRY = "expiry"
    private const val CHANNEL_RECURRING = "recurring"

    // 通知の番号 = 種類ごとの土台 + 品目の id。品目ごとに 1 件ずつ並び、同じ品目の通知は上書きになる
    private const val BASE_LOW = 100_000
    private const val BASE_EXPIRY = 200_000
    private const val BASE_RECURRING = 300_000

    fun lowStock(context: Context, item: Item): Boolean = show(
        context, CHANNEL_LOW, "低在庫のお知らせ", BASE_LOW, item,
        title = if (item.currentQuantity <= 0.0) "${item.name} が在庫切れです" else "${item.name} が残り少なくなりました",
        body = "残り ${Format.quantity(item.currentQuantity, item.unit)}。買い物リストに追加しました。",
    )

    fun expiry(context: Context, item: Item, today: LocalDate): Boolean = show(
        context, CHANNEL_EXPIRY, "期限のお知らせ", BASE_EXPIRY, item,
        title = "${item.name} の期限を確認してください",
        body = item.expiryDate?.let { "${Expiry.label(it, today)}($it)" }.orEmpty(),
    )

    fun recurring(context: Context, item: Item): Boolean = show(
        context, CHANNEL_RECURRING, "定期購入のお知らせ", BASE_RECURRING, item,
        title = "${item.name} はそろそろ購入時期です",
        body = "${item.recurringPurchaseIntervalDays ?: 0}日ごとの定期購入のお知らせです。",
    )

    /**
     * 出したままの通知を片付ける。品目を消した後に残すと、開いても何も出ない。
     * データを入れ替えた(復元)後に残すと、同じ id の別の品目が開く。[itemId] が null なら全部。
     */
    fun cancel(context: Context, itemId: Long?) {
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            if (itemId == null) {
                manager.cancelAll()
            } else {
                listOf(BASE_LOW, BASE_EXPIRY, BASE_RECURRING).forEach { manager.cancel(it + (itemId % 100_000).toInt()) }
            }
        }
    }

    @SuppressLint("MissingPermission") // areNotificationsEnabled() で確かめ、失敗も握っている
    private fun show(
        context: Context,
        channelId: String,
        channelName: String,
        baseId: Int,
        item: Item,
        title: String,
        body: String,
    ): Boolean = runCatching {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false

        // API 26 以上はチャンネルが要る。同じ ID で作り直しても、利用者が変えた設定は壊れない
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT),
        )

        val id = baseId + (item.id % 100_000).toInt()
        // requestCode を通知ごとに変える。PendingIntent の同一判定は extras を見ないので、
        // 同じ番号だと後の通知が前の通知の行き先(品目)を上書きする
        val pending = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(Routes.EXTRA_ITEM_ID, item.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        // 権限が無い端末では SecurityException が飛ぶので runCatching の内側で呼ぶ
        manager.notify(id, notification)
        true
    }.getOrDefault(false)
}
