package com.transcriber.app.doc

/** Небольшое подмножество Markdown → HTML: абзацы, списки (с вложенностью), таблицы, ## заголовки, чекбоксы. */
object MdHtml {
    private val LIST = Regex("""(\s*)(?:([-*])|(\d+)\.)\s+(.*)""")
    private val BOX = Regex("""\[( |x|X)]\s+(.*)""")
    private val HEAD = Regex("""(#{2,3})\s+(.*)""")
    private val SEP = Regex(""":?-{2,}:?""")

    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun inline(text: String): String {
        var t = esc(text)
        t = Regex("""\*\*(.+?)\*\*""").replace(t, "<strong>$1</strong>")
        return Regex("`(.+?)`").replace(t, "<code>$1</code>")
    }

    fun tableCells(rows: List<String>): List<List<String>> =
        rows.map { r -> r.trim().trim('|').split("|").map { it.trim() } }
            .filterNot { r -> r.all { SEP.matches(it) } }

    private fun table(rows: List<String>): String {
        val cells = tableCells(rows)
        if (cells.isEmpty()) return ""
        val head = cells[0].joinToString("") { "<th>${inline(it)}</th>" }
        val body = cells.drop(1).joinToString("") { r -> "<tr>" + r.joinToString("") { "<td>${inline(it)}</td>" } + "</tr>" }
        return "<table><thead><tr>$head</tr></thead><tbody>$body</tbody></table>"
    }

    fun checkbox(text: String): String {
        val b = BOX.matchEntire(text) ?: return text
        return (if (b.groupValues[1] != " ") "☑ " else "☐ ") + b.groupValues[2]
    }

    fun body(md: String): String {
        val out = mutableListOf<String>()
        var lst: String? = null
        var liOpen = false
        var nested = false
        val rows = mutableListOf<String>()

        fun close() {
            if (nested) { out += "</ul>"; nested = false }
            if (liOpen) { out += "</li>"; liOpen = false }
            if (lst != null) { out += "</$lst>"; lst = null }
            if (rows.isNotEmpty()) { out += table(rows); rows.clear() }
        }

        for (line in md.lines()) {
            if (line.trimStart().startsWith("|")) {
                if (lst != null) close()
                rows += line
                continue
            }
            if (rows.isNotEmpty()) close()
            val m = LIST.matchEntire(line)
            if (m != null) {
                val kind = if (m.groupValues[3].isNotEmpty()) "ol" else "ul"
                val text = checkbox(m.groupValues[4])
                if (m.groupValues[1].length >= 2 && lst != null) {
                    if (!nested) { out += "<ul>"; nested = true }
                    out += "<li>${inline(text)}</li>"
                    continue
                }
                if (lst != null && lst != kind) close()
                if (nested) { out += "</ul>"; nested = false }
                if (liOpen) out += "</li>"
                if (lst == null) { out += "<$kind>"; lst = kind }
                out += "<li>${inline(text)}"
                liOpen = true
                continue
            }
            close()
            val h = HEAD.matchEntire(line)
            if (h != null) {
                val n = h.groupValues[1].length
                out += "<h$n>${inline(h.groupValues[2])}</h$n>"
            } else if (line.isNotBlank()) {
                out += "<p>${inline(line)}</p>"
            }
        }
        close()
        return out.joinToString("\n")
    }
}
