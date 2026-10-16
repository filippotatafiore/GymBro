package com.example.gymbro

import com.example.gymbro.session.SpokenNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Run with: right click on this file > Run 'SpokenNumbersTest'
class SpokenNumbersTest {

    @Test
    fun digits() {
        assertEquals(4, SpokenNumbers.find("4"))
        assertEquals(12, SpokenNumbers.find("12"))
        assertEquals(4, SpokenNumbers.find("+4"))
        assertEquals(6, SpokenNumbers.find("6 vasche"))
        assertEquals(8, SpokenNumbers.find("8laps"))
    }

    @Test
    fun englishWords() {
        assertEquals(4, SpokenNumbers.find("four"))
        assertEquals(10, SpokenNumbers.find("Ten"))
        assertEquals(2, SpokenNumbers.find("plus two laps"))
        assertEquals(16, SpokenNumbers.find("sixteen"))
        assertEquals(22, SpokenNumbers.find("twenty-two"))
        assertEquals(35, SpokenNumbers.find("thirty five"))
        assertEquals(40, SpokenNumbers.find("forty"))
        assertEquals(100, SpokenNumbers.find("a hundred"))
        assertEquals(100, SpokenNumbers.find("one hundred"))
    }

    @Test
    fun italianWords() {
        assertEquals(4, SpokenNumbers.find("quattro"))
        assertEquals(8, SpokenNumbers.find("Otto vasche"))
        assertEquals(10, SpokenNumbers.find("più dieci"))
        assertEquals(17, SpokenNumbers.find("diciassette"))
        assertEquals(20, SpokenNumbers.find("venti"))
        assertEquals(21, SpokenNumbers.find("ventuno"))
        assertEquals(22, SpokenNumbers.find("ventidue"))
        assertEquals(23, SpokenNumbers.find("ventitré"))
        assertEquals(28, SpokenNumbers.find("ventotto"))
        assertEquals(31, SpokenNumbers.find("trentuno"))
        assertEquals(48, SpokenNumbers.find("quarantotto"))
        assertEquals(99, SpokenNumbers.find("novantanove"))
        assertEquals(100, SpokenNumbers.find("cento"))
        assertEquals(1, SpokenNumbers.find("ne ho fatta una"))
    }

    @Test
    fun theWakeWordIsIgnored() {
        assertEquals(4, SpokenNumbers.find("GymBro 4"))
        assertEquals(6, SpokenNumbers.find("gym bro sei"))
    }

    @Test
    fun noNumber_nothingToAdd() {
        assertNull(SpokenNumbers.find(""))
        assertNull(SpokenNumbers.find("GymBro"))
        assertNull(SpokenNumbers.find("ok"))
        assertNull(SpokenNumbers.find("ciao come va"))
        // words that sound like numbers, but are not
        assertNull(SpokenNumbers.find("going for a swim"))
        assertNull(SpokenNumbers.find("un attimo"))
    }

    @Test
    fun outOfRange_skipped() {
        assertNull(SpokenNumbers.find("0"))
        assertNull(SpokenNumbers.find("2024"))
        assertNull(SpokenNumbers.find("two hundred"))
        assertEquals(6, SpokenNumbers.find("2024 6"))
    }
}
