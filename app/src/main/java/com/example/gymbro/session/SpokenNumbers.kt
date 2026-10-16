package com.example.gymbro.session

import java.text.Normalizer

// Finds the number said by the user in the text written by the speech recognizer (swim sessions:
// the laps to add, said aloud). The recognizer writes numbers as digits ("4", "12") or as words, in
// Italian or in English ("quattro", "four", "ventidue", "twenty-two"). Texts without a number
// (e.g. "GymBro", "ok") give null: nothing is added. Plain Kotlin: tested in SpokenNumbersTest.kt
object SpokenNumbers {

    // The biggest number accepted (e.g. "2024" in a sentence is not a number of laps)
    const val MAX = 100

    private val units = mapOf(
        // English. Not "to", "too", "for", "won", "ate": common words that sound like numbers
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9,
        // Italian. "uno"/"una" (not "un", the article): "una vasca" is one lap anyway
        "uno" to 1, "una" to 1, "due" to 2, "tre" to 3, "quattro" to 4, "cinque" to 5, "sei" to 6,
        "sette" to 7, "otto" to 8, "nove" to 9,
    )

    private val teens = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "dieci" to 10, "undici" to 11, "dodici" to 12, "tredici" to 13, "quattordici" to 14, "quindici" to 15,
        "sedici" to 16, "diciassette" to 17, "diciotto" to 18, "diciannove" to 19,
    )

    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90,
        "venti" to 20, "trenta" to 30, "quaranta" to 40, "cinquanta" to 50, "sessanta" to 60,
        "settanta" to 70, "ottanta" to 80, "novanta" to 90,
    )

    private val hundred = setOf("hundred", "cento")

    // Words that help the recognizer write numbers (see VoiceLapCounter)
    val words: List<String> = (units.keys + teens.keys + tens.keys + hundred).toList()

    // The first number from 1 to MAX said in the text, or null
    fun find(text: String): Int? {
        val tokens = normalize(text).split(' ').filter { it.isNotEmpty() }
        var i = 0
        while (i < tokens.size) {
            val number = read(tokens, i)
            if (number == null) {
                i++
                continue
            }
            val (value, used) = number
            if (value in 1..MAX) return value
            i += used
        }
        return null
    }

    // The number that starts at tokens[i] and how many tokens it uses, or null if it is not a number
    private fun read(tokens: List<String>, i: Int): Pair<Int, Int>? {
        val token = tokens[i]
        token.toIntOrNull()?.let { return it to 1 }
        val next = tokens.getOrNull(i + 1)

        // "one hundred", "a hundred", "cento"
        if (token in hundred) return 100 to 1
        if ((token == "a" || units[token] != null) && next in hundred) return (units[token] ?: 1) * 100 to 2

        units[token]?.let { return it to 1 }
        teens[token]?.let { return it to 1 }
        tens[token]?.let { ten ->
            // "twenty two", "venti due"
            val unit = next?.let { units[it] }
            return if (unit != null) ten + unit to 2 else ten to 1
        }
        // Italian, one word: "ventidue", "ventuno", "trentotto" (the vowel of the tens is dropped
        // before "uno" and "otto"), "ventitré"
        for ((word, ten) in tens) {
            val stem = word.dropLast(1)
            if (!token.startsWith(stem)) continue
            val rest = token.removePrefix(word).takeIf { token.startsWith(word) } ?: token.removePrefix(stem)
            units[rest]?.let { return ten + it to 1 }
        }
        return null
    }

    // Lowercase, without accents ("ventitré" -> "ventitre") and punctuation ("twenty-two" ->
    // "twenty two", "+4" -> "4"), digits apart from the letters ("4laps" -> "4 laps")
    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("(?<=[0-9])(?=[a-z])|(?<=[a-z])(?=[0-9])"), " ")
            .trim()
}
