// 品目の写真の置き場。書き方は収支管理アプリ(win-loss-tracker)の PhotoStore と同じ。
package io.github.takosasidev.shelftally.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 実体は `filesDir/photos/` に置き、DB(`Item.photoUri`)にはファイル名だけを持つ。
 * 取り込むときに必ず縮める(NFR-08)。一覧のサムネイルに 1200 万画素は要らず、
 * 縮めないとバックアップの zip が実用にならない大きさになる。
 */
object PhotoStore {

    /** 長辺の上限と JPEG の品質。品目の見分けが付けばよいので控えめ。 */
    private const val MAX_EDGE = 1024
    private const val JPEG_QUALITY = 80
    const val DIR_NAME = "photos"

    fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun file(context: Context, name: String): File = File(dir(context), name)

    /** 撮影の一時ファイルの Uri(FileProvider)。カメラのアプリに書かせ、[save] で取り込む。 */
    fun captureUri(context: Context): Uri {
        val f = File(File(context.cacheDir, "capture").apply { mkdirs() }, "capture.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
    }

    /** 選んだ画像・撮った画像を取り込む。成功したらファイル名、失敗したら null(写真 1 枚で登録を止めない)。 */
    suspend fun save(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decodeScaled(context, uri) ?: return@runCatching null
            val rotated = applyExifRotation(context, uri, bitmap)
            val name = "${UUID.randomUUID()}.jpg"
            file(context, name).outputStream().use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (rotated !== bitmap) bitmap.recycle()
            rotated.recycle()
            name
        }.getOrNull()
    }

    /** どの品目からも参照されていない写真を消す(写真の差し替え・品目の削除・復元の後)。 */
    suspend fun pruneOrphans(context: Context, referenced: Set<String>) = withContext(Dispatchers.IO) {
        runCatching {
            dir(context).listFiles()?.forEach { f ->
                if (f.name !in referenced) {
                    f.delete()
                    PhotoLoader.evict(f.name)
                }
            }
        }
        Unit
    }

    /** 大きい画像をそのまま読むとメモリが足りなくなるので、読む段階で間引く。 */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_EDGE || bounds.outHeight / (sample * 2) >= MAX_EDGE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // 間引きは 2 の冪でしか効かないので、残りを最後に縮めて上限に合わせる
        val longest = maxOf(decoded.width, decoded.height)
        if (longest <= MAX_EDGE) return decoded
        val scale = MAX_EDGE.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** カメラは画素を回さず Exif の向きだけを書くので、こちらで焼き込む(縦で撮った写真が横にならないように)。 */
    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        if (degrees == 0f) return bitmap
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }
}

/** 保存済みの写真を表示用に読む。画像のライブラリは足さず、BitmapFactory で間引いて読む。 */
object PhotoLoader {

    /** これ以下の大きさで読んだ物だけ使い回す(一覧のサムネイル)。 */
    private const val CACHEABLE_MAX_PX = 256
    private const val CACHE_BYTES = 4 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun cached(name: String, reqPx: Int): Bitmap? = if (reqPx <= CACHEABLE_MAX_PX) cache.get(name) else null

    /**
     * 写真を [reqPx] 相当まで間引いて読む。ファイルが消えている・壊れている場合は null を返し、
     * 呼ぶ側は既定のアイコンに切り替える(写真 1 枚のために一覧ごと落とさない)。
     */
    suspend fun decodeSampled(context: Context, name: String, reqPx: Int): Bitmap? {
        cached(name, reqPx)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val f = PhotoStore.file(context, name)
                if (!f.exists()) return@runCatching null

                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

                var sample = 1
                while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= reqPx) sample *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()?.also { bmp ->
                if (reqPx <= CACHEABLE_MAX_PX) cache.put(name, bmp)
            }
        }
    }

    fun evict(name: String) {
        cache.remove(name)
    }
}
