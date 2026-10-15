package com.example.gymbro.chat

import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import kotlin.math.roundToInt

// The answers of the coach are written in Markdown: **bold**, *italic*, "- " lists, sometimes
// "# titles" and `code`. ChatFormatting.parse finds the text to show and where its styles go;
// styledText turns them into a text for a TextView.
object ChatFormatting {

    // An item of a list: its characters in the text (start until end) and its marker
    // (e.g. "• " or "2. "): the following lines of the item are indented by the width of the marker
    data class ListItem(val start: Int, val end: Int, val marker: String)

    class Styled(val text: String, val bold: List<IntRange>, val italic: List<IntRange>, val listItems: List<ListItem>)

    private val heading = Regex("""^#{1,6}\s+(.*)$""")
    private val bullet = Regex("""^(\s*)[-*+•]\s+(.*)$""")
    private val numbered = Regex("""^(\s*)(\d{1,3}[.)])\s+(.*)$""")
    private val horizontalLine = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")

    // **bold**, __bold__, `code`, *italic*, _italic_ (not inside words: e.g. "leg_press", "5 * 3")
    private val inlineStyle = Regex(
        """\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|(?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?![\w*])|(?<![\w_])_(?![\s_])(.+?)(?<![\s_])_(?![\w_])"""
    )

    fun parse(markdown: String): Styled {
        val text = StringBuilder()
        val bold = mutableListOf<IntRange>()
        val italic = mutableListOf<IntRange>()
        val listItems = mutableListOf<ListItem>()
        var emptyLineBefore = false

        for (rawLine in markdown.replace("\r\n", "\n").lines()) {
            val line = rawLine.trimEnd()
            // lines of code blocks (```) and horizontal lines (---) are not shown
            if (line.trimStart().startsWith("```") || horizontalLine.matches(line)) continue
            if (line.isBlank()) {
                emptyLineBefore = true
                continue
            }
            // at most one empty line between two paragraphs
            if (text.isNotEmpty()) text.append(if (emptyLineBefore) "\n\n" else "\n")
            emptyLineBefore = false

            val start = text.length
            val headingMatch = heading.matchEntire(line)
            val bulletMatch = bullet.matchEntire(line)
            val numberedMatch = numbered.matchEntire(line)
            when {
                // titles: bold text
                headingMatch != null -> {
                    appendInline(headingMatch.groupValues[1], text, bold, italic)
                    if (text.length > start) bold.add(start until text.length)
                }
                bulletMatch != null -> {
                    // nested lists (2 spaces for each level) are indented more
                    val marker = "    ".repeat(bulletMatch.groupValues[1].length / 2) + "• "
                    text.append(marker)
                    appendInline(bulletMatch.groupValues[2], text, bold, italic)
                    listItems.add(ListItem(start, text.length, marker))
                }
                numberedMatch != null -> {
                    val marker = "    ".repeat(numberedMatch.groupValues[1].length / 2) + numberedMatch.groupValues[2] + " "
                    text.append(marker)
                    appendInline(numberedMatch.groupValues[3], text, bold, italic)
                    listItems.add(ListItem(start, text.length, marker))
                }
                else -> appendInline(line.trim(), text, bold, italic)
            }
        }
        return Styled(text.toString(), bold, italic, listItems)
    }

    // Appends a line without the Markdown symbols, remembering where the bold and italic parts are
    private fun appendInline(line: String, text: StringBuilder, bold: MutableList<IntRange>, italic: MutableList<IntRange>) {
        var last = 0
        for (match in inlineStyle.findAll(line)) {
            text.append(line, last, match.range.first)
            val start = text.length
            val groups = match.groupValues
            when {
                // bold: its content can also contain italic parts
                match.groups[1] != null || match.groups[2] != null -> {
                    appendInline(groups[1] + groups[2], text, bold, italic)
                    bold.add(start until text.length)
                }
                match.groups[3] != null -> text.append(groups[3])
                else -> {
                    appendInline(groups[4] + groups[5], text, bold, italic)
                    italic.add(start until text.length)
                }
            }
            last = match.range.last + 1
        }
        text.append(line, last, line.length)
    }
}

// The answer of the coach with its styles. paint: the one of the TextView, used to measure the
// indent of the lists.
fun styledText(markdown: String, paint: Paint): CharSequence {
    val styled = ChatFormatting.parse(markdown)
    val spannable = SpannableString(styled.text)
    for (range in styled.bold) {
        spannable.setSpan(StyleSpan(Typeface.BOLD), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    for (range in styled.italic) {
        spannable.setSpan(StyleSpan(Typeface.ITALIC), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    // the lines after the first one of a list item start under its text, not under the marker
    for (item in styled.listItems) {
        val indent = paint.measureText(item.marker).roundToInt()
        spannable.setSpan(LeadingMarginSpan.Standard(0, indent), item.start, item.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return spannable
}
