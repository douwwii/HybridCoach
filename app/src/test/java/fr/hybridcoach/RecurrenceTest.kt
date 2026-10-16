package fr.hybridcoach

import fr.hybridcoach.domain.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class RecurrenceTest {
    @Test
    fun inclusiveTuesdaySchedule() {
        val dates =
            Recurrence.weekly(
                LocalDate.parse("2026-10-06"),
                LocalDate.parse("2026-10-27"),
                setOf(DayOfWeek.TUESDAY),
            )
        assertEquals(
            listOf("2026-10-06", "2026-10-13", "2026-10-20", "2026-10-27"),
            dates.map { it.toString() },
        )
    }

    @Test
    fun multipleDaysAcrossYear() {
        val dates =
            Recurrence.weekly(
                LocalDate.parse("2026-12-30"),
                LocalDate.parse("2027-01-05"),
                setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
            )
        assertEquals(listOf("2026-12-31", "2027-01-04"), dates.map { it.toString() })
    }

    @Test
    fun leapDay() {
        assertEquals(
            1,
            Recurrence.weekly(
                    LocalDate.parse("2028-02-29"),
                    LocalDate.parse("2028-02-29"),
                    setOf(DayOfWeek.TUESDAY),
                )
                .size,
        )
    }

    @Test
    fun biweeklyAnchoredToMonday() {
        val dates =
            Recurrence.weekly(
                LocalDate.parse("2026-10-07"),
                LocalDate.parse("2026-10-31"),
                setOf(DayOfWeek.FRIDAY),
                2,
            )
        assertEquals(listOf("2026-10-09", "2026-10-23"), dates.map { it.toString() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun reversedDatesRejected() {
        Recurrence.weekly(
            LocalDate.parse("2026-10-10"),
            LocalDate.parse("2026-10-01"),
            setOf(DayOfWeek.FRIDAY),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyDaysRejected() {
        Recurrence.weekly(LocalDate.now(), LocalDate.now(), emptySet())
    }

    @Test
    fun noMatchingDay() {
        assertTrue(
            Recurrence.weekly(
                    LocalDate.parse("2026-10-16"),
                    LocalDate.parse("2026-10-16"),
                    setOf(DayOfWeek.MONDAY),
                )
                .isEmpty()
        )
    }
}
