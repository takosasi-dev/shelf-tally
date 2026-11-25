// バックアップ zip の中の CSV の読み書き(RFC 4180)。書き方は収支管理アプリ(win-loss-tracker)の CsvExport / CsvImport と同じ。
package io.github.takosasidev.shelftally.core.backup

object Csv {

    const val EOL = "\r\n"

    /** カンマ・引用符・改行・前後の空白があれば引用符で囲む。null は空欄。 */
    fun escape(field: String?): String {
        val value = field ?: return ""
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || value != value.trim()
        if (!needsQuote) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    fun row(fields: List<String?>): String = fields.joinToString(",", postfix = EOL, transform = ::escape)

    /** 引用符の中の改行とカンマを 1 つの欄として読む。空の行も 1 行として返す。 */
    fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var started = false

        fun endRow() {
            fields.add(field.toString())
            field.setLength(0)
            rows.add(fields)
            fields = mutableListOf()
            started = false
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            started = true
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        field.append('"'); i++
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    fields.add(field.toString()); field.setLength(0)
                }
                c == '\r' -> {
                    // CRLF は 1 つの改行として扱う
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                    endRow()
                }
                c == '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (started) endRow()
        return rows
    }

    /**
     * 見出しの名前で引ける形にして 1 行ずつ変換する。列の順が変わっても、後から列が増えても読める。
     * 無い列は空文字。変換が null を返した行は捨てる。
     */
    fun <T> parseKeyed(text: String, transform: (get: (String) -> String) -> T?): List<T> {
        val rows = parseRows(text).filter { r -> r.any { it.isNotBlank() } }
        val names = rows.firstOrNull()?.map { it.trim() } ?: return emptyList()
        return rows.drop(1).mapNotNull { row ->
            transform { key -> names.indexOf(key).let { if (it < 0) "" else row.getOrNull(it).orEmpty() } }
        }
    }
}
