package io.github.takosasidev.shelftally.ui.nav

import android.net.Uri

/** 画面の行き先。ショートカット・通知・ウィジェットからの飛び先もここを見る。 */
object Routes {
    const val ITEMS = "items"
    const val SHOPPING = "shopping"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val SCAN = "scan"
    const val CATEGORIES = "categories"
    const val BACKUP = "backup"

    const val DETAIL = "detail/{id}"
    fun detail(id: Long) = "detail/$id"

    /** 新規登録 / 編集。id が -1 なら新規。barcode はスキャンから引き継ぐ番号(無ければ空)。 */
    const val EDIT = "edit/{id}?barcode={barcode}"
    const val NEW_ID = -1L
    fun edit(id: Long = NEW_ID, barcode: String = "") =
        "edit/$id" + if (barcode.isEmpty()) "" else "?barcode=${Uri.encode(barcode)}"

    /** ショートカットと通知が Intent に載せる行き先。res/xml/shortcuts.xml と同じ文字にする。 */
    const val EXTRA_DESTINATION = "io.github.takosasidev.shelftally.DESTINATION"
    const val DEST_SCAN = "scan"
    const val DEST_SHOPPING = "shopping"

    /** 通知をタップしたときに開く品目。 */
    const val EXTRA_ITEM_ID = "io.github.takosasidev.shelftally.ITEM_ID"
}
