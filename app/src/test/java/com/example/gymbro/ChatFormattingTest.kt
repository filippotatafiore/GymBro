package com.example.gymbro

import com.example.gymbro.chat.ChatFormatting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Run with: right click on this file > Run 'ChatFormattingTest'
class ChatFormattingTest {

    private fun ChatFormatting.Styled.boldTexts() = bold.map { text.substring(it.first, it.last + 1) }
    private fun ChatFormatting.Styled.italicTexts() = italic.map { text.substring(it.first, it.last + 1) }

    @Test
    fun bold_symbolsRemoved() {
        val styled = ChatFormatting.parse("**Squat**: keep your __back__ straight")
        assertEquals("Squat: keep your back straight", styled.text)
        assertEquals(listOf("Squat", "back"), styled.boldTexts())
    }

    @Test
    fun italic_notInsideWordsOrCalculations() {
        val styled = ChatFormatting.parse("Use *light* weights for leg_press and lat_pulldown: 3 * 10 reps, _slowly_")
        assertEquals("Use light weights for leg_press and lat_pulldown: 3 * 10 reps, slowly", styled.text)
        assertEquals(listOf("light", "slowly"), styled.italicTexts())
    }

    @Test
    fun italicInsideBold() {
        val styled = ChatFormatting.parse("**very *important* tip**")
        assertEquals("very important tip", styled.text)
        assertEquals(listOf("very important tip"), styled.boldTexts())
        assertEquals(listOf("important"), styled.italicTexts())
    }

    @Test
    fun bulletLists_withMarkerAndIndent() {
        val styled = ChatFormatting.parse("Tips:\n- **Sets**: 3\n* Reps: 10\n  - nested")
        assertEquals("Tips:\n• Sets: 3\n• Reps: 10\n    • nested", styled.text)
        assertEquals(listOf("Sets"), styled.boldTexts())
        assertEquals(listOf("• ", "• ", "    • "), styled.listItems.map { it.marker })
        // each item covers its whole line
        assertEquals(listOf("• Sets: 3", "• Reps: 10", "    • nested"),
            styled.listItems.map { styled.text.substring(it.start, it.end) })
    }

    @Test
    fun numberedLists_keepTheirNumber() {
        val styled = ChatFormatting.parse("1. Warm up\n2) Lift")
        assertEquals("1. Warm up\n2) Lift", styled.text)
        assertEquals(listOf("1. ", "2) "), styled.listItems.map { it.marker })
    }

    @Test
    fun titles_becomeBold() {
        val styled = ChatFormatting.parse("## Weekly plan\nMonday: legs")
        assertEquals("Weekly plan\nMonday: legs", styled.text)
        assertEquals(listOf("Weekly plan"), styled.boldTexts())
    }

    @Test
    fun emptyLines_atMostOneBetweenParagraphs() {
        val styled = ChatFormatting.parse("\n\nFirst\n\n\n\nSecond\n---\n\nThird\n\n")
        assertEquals("First\n\nSecond\n\nThird", styled.text)
    }

    @Test
    fun code_symbolsRemoved() {
        val styled = ChatFormatting.parse("Use `RPE 8`\n```\nsquat 5x5\n```")
        assertEquals("Use RPE 8\nsquat 5x5", styled.text)
        assertTrue(styled.bold.isEmpty() && styled.italic.isEmpty())
    }

    @Test
    fun plainText_unchanged() {
        val text = "Train 3 times a week: it's enough to improve. Rest 48 h between sessions!"
        assertEquals(text, ChatFormatting.parse(text).text)
    }
}
