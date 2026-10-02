package app.voidfiles.data

/**
 * File name rules. Android's storage layer refuses to create anything below a folder whose name contains
 * control characters (e.g. a line break) or characters that FAT/exFAT cannot store.
 */
object Names {
    private const val FORBIDDEN = "\"*:<>?\\|"

    private fun isBad(c: Char) = c.code < 0x20 || c.code == 0x7F || c in FORBIDDEN

    fun hasInvalidChars(name: String): Boolean = name.any { isBad(it) }

    /** Replaces line breaks/tabs with a space and other forbidden characters with "_". */
    fun sanitize(name: String): String {
        val sb = StringBuilder()
        for (c in name) {
            when {
                c == '\n' || c == '\r' || c == '\t' -> sb.append(' ')
                isBad(c) -> sb.append('_')
                else -> sb.append(c)
            }
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trim().ifEmpty { "_" }
    }

    /** For display: line breaks and tabs become spaces so names stay on one line. */
    fun display(name: String): String = if (name.any { it == '\n' || it == '\r' || it == '\t' }) {
        name.replace(Regex("[\\r\\n\\t]+"), " ")
    } else name
}
