// バックアップ zip の入出力。書き方は収支管理アプリ(win-loss-tracker)の BackupManager と同じ。
package io.github.takosasidev.shelftally.data.backup

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import io.github.takosasidev.shelftally.core.backup.BackupContent
import io.github.takosasidev.shelftally.core.backup.BackupFormat
import io.github.takosasidev.shelftally.data.PhotoStore
import io.github.takosasidev.shelftally.data.ShelfRepository
import io.github.takosasidev.shelftally.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** インポートの読み取りの結果。パスワードの誤りと版の非互換を握りつぶさずに区別する。 */
sealed interface BackupReadResult {
    data class Ok(val content: BackupContent) : BackupReadResult

    /** 暗号化した zip だが、パスワードが渡されていない。 */
    data object NeedPassword : BackupReadResult
    data class WrongPassword(val message: String) : BackupReadResult

    /** 版の非互換など、復元してはいけない中身。 */
    data class Incompatible(val message: String) : BackupReadResult
    data class Error(val message: String) : BackupReadResult
}

/**
 * FR-18 / FR-19 / FR-20 の zip の読み書き。
 *
 * zip の読み書きは Zip4j に揃える(平文と暗号化で処理を分けないため)。Zip4j は File を求めるので、
 * SAF の Uri とは cacheDir の一時ファイルを挟んでやり取りする。
 */
object BackupManager {

    const val MANUAL_PREFIX = "shelftally-backup-"
    const val AUTO_PREFIX = "shelftally-auto-"
    const val ZIP_EXT = ".zip"
    const val ZIP_MIME = "application/zip"

    private const val PREFS = "backup_state"
    private const val KEY_TREE_URI = "treeUri"
    private const val KEY_LAST_AUTO_ERROR = "lastAutoBackupError"

    /** zip から取り出した写真の一時の置き場。復元が決まるまで filesDir には書かない(確認の画面でやめられる)。 */
    private const val PHOTO_STAGING = "restore-photos"

    private val stampFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.systemDefault())

    fun manualFileName(at: Instant = Instant.now()) = "$MANUAL_PREFIX${stampFmt.format(at)}$ZIP_EXT"
    fun autoFileName(at: Instant = Instant.now()) = "$AUTO_PREFIX${stampFmt.format(at)}$ZIP_EXT"

    // ---- 書き出し ----------------------------------------------------------

    /** FR-18 / FR-19: 今のデータを zip にして [out] に流す。password が空なら平文の zip。 */
    suspend fun writeBackup(context: Context, out: OutputStream, password: String?) = withContext(Dispatchers.IO) {
        // 途中で失敗しても out を閉じ、一時ファイルを残さない
        out.use { target ->
            val content = ShelfRepository.get(context).snapshot()
            // 参照されている写真だけ(差し替えの残りまで入れても、復元先で誰も参照しない)
            val referenced = content.items.mapNotNull { it.photoUri }.toSet()
            val photos = PhotoStore.dir(context).listFiles()?.filter { it.name in referenced }.orEmpty()
            val texts = listOf(
                BackupFormat.ENTRY_MANIFEST to BackupFormat.manifest(
                    schemaVersion = AppDatabase.VERSION,
                    appVersionName = appVersionName(context),
                    exportedAt = Instant.now(),
                    itemCount = content.items.size,
                    photoCount = photos.size,
                ),
                BackupFormat.ENTRY_CATEGORIES to BackupFormat.categoriesCsv(content.categories),
                BackupFormat.ENTRY_ITEMS to BackupFormat.itemsCsv(content.items),
                BackupFormat.ENTRY_LOGS to BackupFormat.logsCsv(content.logs),
                BackupFormat.ENTRY_SHOPPING to BackupFormat.shoppingCsv(content.shopping),
                BackupFormat.ENTRY_SETTINGS to BackupFormat.settingsProperties(content.settings),
            )
            val temp = File.createTempFile("st-export", ZIP_EXT, context.cacheDir)
            try {
                zipTo(temp, texts, photos, password)
                temp.inputStream().use { it.copyTo(target) }
            } finally {
                temp.delete()
            }
        }
    }

    private fun zipTo(target: File, texts: List<Pair<String, String>>, photos: List<File>, password: String?) {
        target.delete() // Zip4j は既にあるファイルに追記するので、必ず作り直す
        val encrypted = !password.isNullOrEmpty()
        val zip = if (encrypted) ZipFile(target, password!!.toCharArray()) else ZipFile(target)
        // 暗号化の指定はエントリごと。写真も同じ params を通すので、写真だけ平文で入ることはない
        fun params(name: String) = ZipParameters().apply {
            fileNameInZip = name
            if (encrypted) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        zip.use { z ->
            texts.forEach { (name, text) ->
                ByteArrayInputStream(text.toByteArray()).use { z.addStream(it, params(name)) }
            }
            photos.forEach { photo ->
                // 1 枚入らなくても、バックアップ全体は失敗させない
                runCatching { z.addFile(photo, params(BackupFormat.ENTRY_PHOTOS_DIR + photo.name)) }
            }
        }
    }

    suspend fun exportToUri(context: Context, uri: Uri, password: String?) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IllegalStateException("保存先を開けませんでした")
        writeBackup(context, out, password)
    }

    // ---- 読み取り ----------------------------------------------------------

    suspend fun readBackup(context: Context, uri: Uri, password: String?): BackupReadResult =
        withContext(Dispatchers.IO) {
            val temp = File.createTempFile("st-import", ZIP_EXT, context.cacheDir)
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: return@withContext BackupReadResult.Error("ファイルを開けませんでした。選び直してください。")
                input.use { src -> FileOutputStream(temp).use { src.copyTo(it) } }
                readBackupFile(temp, password, File(context.cacheDir, PHOTO_STAGING))
            } catch (e: SecurityException) {
                BackupReadResult.Error("ファイルを読む許可がありません。もう一度ファイルを選び直してください。")
            } catch (e: Exception) {
                BackupReadResult.Error("ファイルを読み込めませんでした: ${reason(e)}")
            } finally {
                temp.delete()
            }
        }

    /** [photoStagingDir] を渡すと、zip の中の `photos/` をそこに取り出す。null なら枚数を数えるだけ。 */
    fun readBackupFile(file: File, password: String?, photoStagingDir: File? = null): BackupReadResult {
        val encrypted = try {
            ZipFile(file).use { it.isEncrypted }
        } catch (e: Exception) {
            return BackupReadResult.Error(
                "バックアップの zip として読み取れませんでした。このアプリのエクスポートで作った zip を選び直してください。",
            )
        }
        if (encrypted && password.isNullOrEmpty()) return BackupReadResult.NeedPassword

        var photoCount = 0
        val texts: Map<String, String> = try {
            val zip = if (password.isNullOrEmpty()) ZipFile(file) else ZipFile(file, password.toCharArray())
            zip.use { z ->
                val loaded = BackupFormat.TEXT_ENTRIES.mapNotNull { name ->
                    val header = z.getFileHeader(name) ?: return@mapNotNull null
                    name to z.getInputStream(header).use { s -> s.readBytes().toString(Charsets.UTF_8) }
                }.toMap()
                photoCount = if (photoStagingDir != null) {
                    extractPhotos(z, photoStagingDir)
                } else {
                    z.fileHeaders.count { photoEntryName(it.fileName) != null }
                }
                loaded
            }
        } catch (e: ZipException) {
            // AES はパスワードの検証で すぐに落ちる。誤ったまま読み進めて壊れたデータを入れない
            return if (encrypted) {
                BackupReadResult.WrongPassword("パスワードが違います。もう一度入力してください。")
            } else {
                BackupReadResult.Error("バックアップの中身を読み取れませんでした: ${reason(e)}")
            }
        } catch (e: Exception) {
            return BackupReadResult.Error("バックアップの中身を読み取れませんでした: ${reason(e)}")
        }

        // 版の非互換は必ずここで止める
        val manifest = texts[BackupFormat.ENTRY_MANIFEST]
        BackupFormat.validateManifest(manifest, AppDatabase.VERSION)?.let { return BackupReadResult.Incompatible(it) }
        val itemsCsv = texts[BackupFormat.ENTRY_ITEMS]
            ?: return BackupReadResult.Incompatible("バックアップに ${BackupFormat.ENTRY_ITEMS} が入っていません。復元を中止しました。")

        return BackupReadResult.Ok(
            BackupContent(
                categories = BackupFormat.parseCategoriesCsv(texts[BackupFormat.ENTRY_CATEGORIES].orEmpty()),
                items = BackupFormat.parseItemsCsv(itemsCsv),
                logs = BackupFormat.parseLogsCsv(texts[BackupFormat.ENTRY_LOGS].orEmpty()),
                shopping = BackupFormat.parseShoppingCsv(texts[BackupFormat.ENTRY_SHOPPING].orEmpty()),
                settings = BackupFormat.parseSettings(texts[BackupFormat.ENTRY_SETTINGS]),
                exportedAt = BackupFormat.manifestValue(manifest, BackupFormat.KEY_EXPORTED_AT)
                    ?.let { v -> runCatching { Instant.parse(v) }.getOrNull() },
                appVersionName = BackupFormat.manifestValue(manifest, BackupFormat.KEY_APP_VERSION),
                // manifest の自己申告ではなく、実際に zip に入っていた枚数
                photoCount = photoCount,
            ),
        )
    }

    /**
     * `photos/<ファイル名>` のエントリなら、ファイル名の部分を返す。それ以外は null。
     * ファイル名の部分だけを使い、さらに [BackupFormat.isSafePhotoName] を通す(`photos/../../x` のような
     * 名前をそのまま使うと、アプリのフォルダの外に書かされる)。
     */
    private fun photoEntryName(entry: String?): String? {
        val raw = entry ?: return null
        if (!raw.startsWith(BackupFormat.ENTRY_PHOTOS_DIR) || raw.endsWith("/")) return null
        return raw.substringAfterLast('/').substringAfterLast('\\').takeIf(BackupFormat::isSafePhotoName)
    }

    private fun extractPhotos(zip: ZipFile, staging: File): Int {
        // 前に読んだバックアップの残りが混ざらないように空にする
        staging.deleteRecursively()
        staging.mkdirs()
        var count = 0
        zip.fileHeaders.forEach { header ->
            val name = photoEntryName(header.fileName) ?: return@forEach
            runCatching {
                zip.getInputStream(header).use { input -> FileOutputStream(File(staging, name)).use { input.copyTo(it) } }
                count++
            }
        }
        return count
    }

    /** FR-18: 今のデータを上書きして復元する。[readBackup] が取り出しておいた写真も戻す。 */
    suspend fun restore(context: Context, content: BackupContent) = withContext(Dispatchers.IO) {
        // 写真を先に置く。replaceAll の最後の掃除が、参照されない写真(古い分)を消す
        val staging = File(context.cacheDir, PHOTO_STAGING)
        val target = PhotoStore.dir(context)
        staging.listFiles()?.forEach { src ->
            runCatching {
                val dst = File(target, src.name)
                // 同じ名前の写真が既にあれば上書きしない。入れ替えが途中で失敗して DB が元に戻っても、
                // 今の品目の写真が差し替わったままにならないように(名前は UUID なので、同じ名前は同じ写真)
                if (dst.exists()) return@runCatching
                if (!src.renameTo(dst)) src.inputStream().use { input -> FileOutputStream(dst).use { input.copyTo(it) } }
            }
        }
        staging.deleteRecursively()
        ShelfRepository.get(context).replaceAll(content)
    }

    // ---- 自動バックアップの保存先のフォルダ(DocumentsContract をそのまま使う) --------

    fun treeUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TREE_URI, null)?.let(Uri::parse)

    /** OpenDocumentTree の結果を覚える。以後はフォルダを選び直さずに書き込める。 */
    fun persistTree(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
        true
    }.getOrDefault(false)

    /** 許可がまだ生きていて、実際に中を読めるか。フォルダを動かされると、許可は残ったまま読み取りだけ失敗する。 */
    fun hasTreeAccess(context: Context, treeUri: Uri): Boolean {
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }
        if (!granted) return false
        return runCatching { queryChildren(context, treeUri)?.use { true } ?: false }.getOrDefault(false)
    }

    fun treeDisplayName(context: Context, treeUri: Uri): String? = runCatching {
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** 覚えたフォルダに zip を作って書き出す。失敗は例外で上に投げる(黙って失敗しない)。 */
    suspend fun exportToTree(context: Context, treeUri: Uri, fileName: String, password: String?): Uri =
        withContext(Dispatchers.IO) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val target = DocumentsContract.createDocument(context.contentResolver, parent, ZIP_MIME, fileName)
                ?: throw IllegalStateException("保存先のフォルダにファイルを作れませんでした")
            try {
                exportToUri(context, target, password)
            } catch (e: Exception) {
                // 失敗や取り消しで、空や途中までの zip を残さない。残すと世代の数に入って、読める古い zip のほうが
                // 先に消される。消すのはここで作ったファイルだけ(手動の書き出しで選ばれた既存のファイルは消さない)
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
                throw e
            }
            target
        }

    private fun queryChildren(context: Context, treeUri: Uri): Cursor? =
        context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )

    /**
     * FR-20: 保持する世代数を超えた自動バックアップを古い順に消す。
     * ファイル名に yyyyMMdd-HHmm を入れているので、名前の降順 = 新しい順。手動のバックアップは対象外。
     */
    fun pruneAutoBackups(context: Context, treeUri: Uri, keepCount: Int): Int {
        val children = runCatching {
            queryChildren(context, treeUri)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        add(name to DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
        var deleted = 0
        children.filter { it.first.startsWith(AUTO_PREFIX) && it.first.endsWith(ZIP_EXT) }
            .sortedByDescending { it.first }
            .drop(keepCount.coerceAtLeast(1))
            .forEach { (_, uri) ->
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    .onSuccess { if (it) deleted++ }
            }
        return deleted
    }

    // ---- 自動バックアップの最後の結果 --------------------------------------

    fun lastAutoBackupError(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_AUTO_ERROR, null)

    fun setLastAutoBackupError(context: Context, message: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (message == null) remove(KEY_LAST_AUTO_ERROR) else putString(KEY_LAST_AUTO_ERROR, message)
        }.apply()
    }

    fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    private fun reason(e: Throwable): String = e.message ?: e.javaClass.simpleName
}
