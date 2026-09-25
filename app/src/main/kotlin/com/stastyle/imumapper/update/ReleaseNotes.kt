package com.stastyle.imumapper.update

/**
 * Turns GitHub release notes (markdown, often auto-generated) into plain text for a Compose
 * [androidx.compose.material3.Text]. This is not a markdown parser: it removes the markers that
 * would otherwise show up as noise and keeps the words, one line per list item.
 */
object ReleaseNotes {

    private val htmlComment = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val htmlTag = Regex("</?[a-zA-Z][^>]*>")
    private val image = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val autoLink = Regex("<(https?://[^>]+)>")
    private val heading = Regex("^#{1,6}\\s*")
    private val bullet = Regex("^[-*+]\\s+")
    private val numbered = Regex("^(\\d+)[.)]\\s+")
    private val quote = Regex("^>\\s?")
    private val rule = Regex("^([-*_])\\1{2,}\\s*$")
    private val strong = Regex("(\\*\\*|__)(.+?)\\1")
    private val emphasis = Regex("(?<![\\w*])[*_](?=\\S)(.+?)(?<=\\S)[*_](?![\\w*])")
    private val code = Regex("`+([^`]*)`+")
    private val blankRuns = Regex("\\n{3,}")

    fun plainText(markdown: String): String {
        if (markdown.isBlank()) return ""
        // Autolinks look like tags, so they are unwrapped before the tag stripper sees them.
        val withoutHtml = markdown.replace("\r\n", "\n")
            .replace(autoLink, "$1")
            .replace(htmlComment, "")
            .replace(htmlTag, "")
        val lines = ArrayList<String>()
        var inFence = false
        for (raw in withoutHtml.lines()) {
            val trimmed = raw.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence
                continue
            }
            if (inFence) {
                lines += raw
                continue
            }
            if (rule.matches(trimmed)) continue
            val nested = raw.length - raw.trimStart().length >= 2 && bullet.containsMatchIn(trimmed)
            var line = trimmed
            line = heading.replace(line, "")
            line = quote.replace(line, "")
            line = bullet.replace(line, if (nested) "    - " else "• ")
            line = numbered.replace(line, "$1. ")
            line = inline(line)
            lines += line
        }
        return blankRuns.replace(lines.joinToString("\n"), "\n\n").trim()
    }

    private fun inline(text: String): String {
        var line = text
        line = image.replace(line, "$1")
        line = link.replace(line, "$1")
        line = code.replace(line, "$1")
        line = strong.replace(line, "$2")
        line = emphasis.replace(line, "$1")
        return line
    }
}
