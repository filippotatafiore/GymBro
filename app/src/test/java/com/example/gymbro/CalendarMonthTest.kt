package com.example.gymbro

import com.example.gymbro.home.CalendarMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

// Run with: right click on this file > Run 'CalendarMonthTest'
class CalendarMonthTest {

    private val october2026 = YearMonth.of(2026, 10)  // starts on Thursday, 31 days

    @Test
    fun weekStartingOnMonday_threeEmptyCellsBeforeThe1st() {
        val cells = CalendarMonth.cells(october2026, DayOfWeek.MONDAY)

        assertEquals(listOf(null, null, null, LocalDate.of(2026, 10, 1)), cells.take(4))
        assertEquals(35, cells.size)  // 5 full weeks
        assertEquals(LocalDate.of(2026, 10, 31), cells[33])
        assertNull(cells[34])
    }

    @Test
    fun weekStartingOnSunday_fourEmptyCellsBeforeThe1st() {
        val cells = CalendarMonth.cells(october2026, DayOfWeek.SUNDAY)

        assertEquals(LocalDate.of(2026, 10, 1), cells[4])
        assertEquals(0, cells.size % 7)
    }

    @Test
    fun monthStartingOnTheFirstDayOfTheWeek_noEmptyCellBefore() {
        // February 2027 starts on Monday and has 28 days: exactly 4 weeks
        val cells = CalendarMonth.cells(YearMonth.of(2027, 2), DayOfWeek.MONDAY)

        assertEquals(28, cells.size)
        assertEquals(LocalDate.of(2027, 2, 1), cells.first())
    }

    @Test
    fun weekDays_startFromTheGivenDay() {
        assertEquals(
            listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY),
            CalendarMonth.weekDays(DayOfWeek.SUNDAY)
        )
    }
}
