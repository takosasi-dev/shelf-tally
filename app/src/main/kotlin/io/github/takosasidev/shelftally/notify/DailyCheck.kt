package io.github.takosasidev.shelftally.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.takosasidev.shelftally.core.calc.Expiry
import io.github.takosasidev.shelftally.core.calc.Recurring
import io.github.takosasidev.shelftally.data.ShelfRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * FR-09 / FR-12: 1 日 1 回の見回り。期限が近い品目と、定期購入の時期が来た品目を通知する。
 * 何を通知するかは :core(Expiry / Recurring)が決める。アプリの起動時にも同じ物を呼ぶ。
 */
object DailyCheck {

    private const val PREFS = "notify_state"
    private const val KEY_EXPIRY_NOTIFIED = "expiryNotified"

    /** 起動時の見回りとアラームが重なっても、同じ品目を 2 回通知しない(後の分は、前の分が付けた印を見る)。 */
    private val mutex = Mutex()

    suspend fun run(context: Context): Unit = mutex.withLock {
        val repo = ShelfRepository.get(context)
        val items = repo.itemsOnce()
        val settings = repo.settingsOnce()
        val today = LocalDate.now()

        // 期限。通知できた品目だけ印を付ける(権限をもらう前の分は、もらった後に通知する)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = Expiry.pruneKeys(items, prefs.getStringSet(KEY_EXPIRY_NOTIFIED, emptySet()).orEmpty())
        val shown = Expiry.toNotify(items, today, settings.expiryAlertLeadDays, notified)
            .filter { Notifier.expiry(context, it, today) }
            .map(Expiry::notifyKey)
        prefs.edit().putStringSet(KEY_EXPIRY_NOTIFIED, notified + shown).apply()

        // 定期購入。買い物リストに載っている品目は通知せず、今回の分を済みにする
        val now = Instant.now()
        val plan = Recurring.plan(items, repo.shoppingOnce(), now)
        val handled = plan.suppressed.map { it.id } + plan.notify.filter { Notifier.recurring(context, it) }.map { it.id }
        if (handled.isNotEmpty()) repo.markRecurringHandled(handled, now)
    }
}

/**
 * AlarmManager で「次の 9:00」に 1 回だけ鳴る、時刻のずれを許すアラームを仕込む(WorkManager は使わない)。
 * 秒単位の正確さは要らないので、SCHEDULE_EXACT_ALARM の権限は求めない。
 * 鳴るたびに [DailyCheckReceiver] が翌日の分を積み直す。
 */
object DailyCheckScheduler {

    /** 見回りの時刻(時)。朝、買い物に出る前に気付ける時刻。 */
    private const val HOUR = 9
    private const val REQUEST_CODE = 4001

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val now = LocalDateTime.now()
        var next = now.withHour(HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, DailyCheckReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending) }
    }
}

/**
 * アラームと、端末の起動(再起動でアラームが消えるので積み直す)の両方で呼ばれる。
 * onReceive は同期のメソッドだが DB を読むので、goAsync() で猶予をもらって IO のスレッドで処理する。
 */
class DailyCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { DailyCheck.run(appContext) }
                DailyCheckScheduler.schedule(appContext)
            } finally {
                // 何が起きても終わりを知らせる(知らせないと、OS がこのアプリを「応答なし」と数える)
                pending.finish()
            }
        }
    }
}
