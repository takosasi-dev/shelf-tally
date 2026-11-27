package io.github.takosasidev.shelftally.data.backup

import android.content.Context
import io.github.takosasidev.shelftally.data.ShelfRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * FR-20: 起動時チェック方式の自動バックアップ。WorkManager は使わない。
 * MainActivity が起動のたびに IO のスレッドから 1 回だけ呼ぶ。
 * 失敗は [BackupManager.lastAutoBackupError] に残し、バックアップの画面に出す(黙って失敗しない)。
 */
object AutoBackup {

    const val ERR_NO_FOLDER =
        "自動バックアップに失敗しました。保存先のフォルダが未設定です。バックアップの画面で保存先を選んでください。"

    const val ERR_NO_ACCESS =
        "自動バックアップに失敗しました。保存先のフォルダを開けません(動かしたか消した可能性があります)。" +
            "バックアップの画面で保存先を選び直してください。"

    /** 画面の作り直しで続けて呼ばれても、同時には 1 つだけ。後の分は、前の分が書いた日時を見てやめる。 */
    private val mutex = Mutex()

    /** バックアップを取ったら true。 */
    suspend fun runIfDue(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val repo = ShelfRepository.get(context)
            val settings = repo.settingsOnce()
            if (!settings.autoBackupEnabled) return@withContext false

            val now = Instant.now()
            val last = settings.lastAutoBackupAt
            val intervalDays = settings.autoBackupIntervalDays.coerceAtLeast(1).toLong()
            if (last != null && now.isBefore(last.plus(intervalDays, ChronoUnit.DAYS))) return@withContext false

            val treeUri = BackupManager.treeUri(context) ?: return@withContext fail(context, ERR_NO_FOLDER)
            if (!BackupManager.hasTreeAccess(context, treeUri)) return@withContext fail(context, ERR_NO_ACCESS)

            try {
                BackupManager.exportToTree(context, treeUri, BackupManager.autoFileName(now), password = null)
                // 間隔を何度も過ぎていても、次の起点は now。過ぎた回数の分だけ続けて取ることはない
                repo.updateSettings { it.copy(lastAutoBackupAt = now) }
                BackupManager.pruneAutoBackups(context, treeUri, settings.autoBackupKeepCount)
                BackupManager.setLastAutoBackupError(context, null)
                true
            } catch (e: CancellationException) {
                throw e // 取り消しは失敗として残さない
            } catch (e: Exception) {
                fail(context, "自動バックアップに失敗しました(${e.message ?: e.javaClass.simpleName})。バックアップの画面で保存先を確かめてください。")
            }
        }
    }

    private fun fail(context: Context, message: String): Boolean {
        BackupManager.setLastAutoBackupError(context, message)
        return false
    }
}
