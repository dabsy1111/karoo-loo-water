package io.github.dan.loowater

import io.github.dan.loowater.OpeningHours.Status.CLOSED
import io.github.dan.loowater.OpeningHours.Status.OPEN
import io.github.dan.loowater.OpeningHours.Status.UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class OpeningHoursTest {
    // Perth CBD, AWST (UTC+8, no DST)
    private val lat = -31.95
    private val lng = 115.86
    private val tz = TimeZone.getTimeZone("Australia/Perth")

    /** 2026-10-16 is a Friday. */
    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = Calendar.OCTOBER): Calendar =
        Calendar.getInstance(tz).apply {
            clear()
            set(2026, month, day, hour, minute)
        }

    private fun check(expected: OpeningHours.Status, value: String?, cal: Calendar, ahead: Int = 0) =
        assertEquals(value, expected, OpeningHours.status(value, cal, lat, lng, ahead))

    @Test fun missingIsUnknown() = check(UNKNOWN, null, at(16, 12))

    @Test fun allDay() = check(OPEN, "24/7", at(16, 3))

    @Test fun simpleWeekday() {
        val v = "Mo-Fr 08:00-18:00"
        check(OPEN, v, at(16, 9))
        check(CLOSED, v, at(16, 19))
        check(CLOSED, v, at(17, 9)) // Saturday
    }

    @Test fun laterRuleOverrides() {
        val v = "Mo-Su 06:00-20:00; Fr 10:00-12:00"
        check(CLOSED, v, at(16, 8))
        check(OPEN, v, at(17, 8))
    }

    @Test fun additiveRule() {
        val v = "Mo-Th 08:00-12:00, Fr 13:00-17:00"
        check(OPEN, v, at(16, 14))
        check(OPEN, v, at(15, 9))
    }

    @Test fun overnightSpill() {
        val v = "Fr 22:00-02:00"
        check(OPEN, v, at(16, 23))
        check(OPEN, v, at(17, 1)) // Saturday 01:00 belongs to Friday's rule
        check(CLOSED, v, at(17, 3))
    }

    @Test fun offRule() = check(CLOSED, "Mo-Su 06:00-20:00; Fr off", at(16, 12))

    @Test fun holidayRuleIgnored() = check(OPEN, "Mo-Su 06:00-20:00; PH off", at(16, 12))

    @Test fun closingSoonCountsAsClosed() {
        check(CLOSED, "Mo-Su 06:00-18:00", at(16, 17, 55), ahead = 10)
        check(OPEN, "Mo-Su 06:00-18:00", at(16, 17, 40), ahead = 10)
    }

    @Test fun sunriseSunset() {
        // Perth mid-October: sunrise ~05:35, sunset ~18:20
        check(OPEN, "sunrise-sunset", at(16, 12))
        check(CLOSED, "sunrise-sunset", at(16, 22))
        check(CLOSED, "sunrise-sunset", at(16, 4))
    }

    @Test fun sunsetOffset() = check(CLOSED, "06:00-(sunset-01:00)", at(16, 17, 45))

    @Test fun months() {
        val v = "Nov-Mar 06:00-21:00; Apr-Oct 07:00-18:00"
        check(CLOSED, v, at(16, 19))
        check(OPEN, v, at(16, 19, month = Calendar.DECEMBER))
    }

    @Test fun wrapWeekdays() = check(OPEN, "Fr-Mo 09:00-17:00", at(19, 10)) // Monday

    @Test fun multipleSpans() {
        val v = "Mo-Fr 08:00-12:00,13:00-17:00"
        check(CLOSED, v, at(16, 12, 30))
        check(OPEN, v, at(16, 13, 30))
    }

    @Test fun commentsStripped() = check(OPEN, "Mo-Su 06:00-22:00 \"gates locked overnight\"", at(16, 12))

    @Test fun specificDatesAreUnknown() = check(UNKNOWN, "24/7; Dec 25 off", at(16, 12))

    @Test fun garbageIsUnknown() = check(UNKNOWN, "ask at the counter", at(16, 12))

    @Test fun selectorWithoutTimesIsAllDay() = check(OPEN, "Mo-Fr", at(16, 2))
}
