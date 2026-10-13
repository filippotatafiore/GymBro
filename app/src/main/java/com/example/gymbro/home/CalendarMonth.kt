package com.example.gymbro.home

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

// The days of a month as shown in a calendar (plain Kotlin: tested in CalendarMonthTest.kt)
object CalendarMonth {

    // The 7 days of the week, starting from the first one of the country of the phone
    // (Monday in Italy, Sunday in the USA)
    fun weekDays(firstDayOfWeek: DayOfWeek): List<DayOfWeek> =
        (0L until 7L).map { firstDayOfWeek.plus(it) }

    // The cells of the calendar, week by week (7 per row): null = empty cell before the first
    // day of the month or after the last one
    fun cells(month: YearMonth, firstDayOfWeek: DayOfWeek): List<LocalDate?> {
        val emptyBefore = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val days = (1..month.lengthOfMonth()).map { month.atDay(it) }
        val cells = List(emptyBefore) { null } + days
        val emptyAfter = (7 - cells.size % 7) % 7
        return cells + List(emptyAfter) { null }
    }
}
