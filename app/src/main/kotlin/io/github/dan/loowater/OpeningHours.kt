package io.github.dan.loowater

import java.util.Calendar
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Evaluates the common subset of OpenStreetMap `opening_hours` values:
 *
 *  - `24/7`
 *  - rules separated by `;` (later rules override earlier ones for the days they match)
 *  - additional rules separated by `, ` (add to earlier ones instead of overriding)
 *  - month selectors: `Apr-Oct`, `Nov,Dec`
 *  - weekday selectors: `Mo-Fr`, `Sa,Su`, `Fr-Mo` (PH/SH rules are skipped: we can't know holidays)
 *  - time spans: `08:00-18:00`, `22:00-02:00`, `18:00+`, `sunrise-sunset`, `(sunset-00:30)`, `dawn`, `dusk`
 *  - `off` / `closed` / `open`
 *
 * Anything else (specific dates, week numbers, nth weekdays) gives [Status.UNKNOWN] rather than a
 * guess. Unknown places are still offered as destinations; only a confident CLOSED excludes one.
 */
object OpeningHours {

    enum class Status { OPEN, CLOSED, UNKNOWN }

    /** A calendar day, in the rider's local time zone. weekday: 0 = Monday ... 6 = Sunday. */
    data class Day(
        val year: Int,
        val month: Int,
        val dayOfMonth: Int,
        val weekday: Int,
        val dayOfYear: Int,
        val utcOffsetMin: Int,
    )

    /**
     * Status at [at], treating a place that shuts within [minutesAhead] as closed
     * (no point routing to a toilet that locks before you arrive).
     */
    fun status(value: String?, at: Calendar, lat: Double, lng: Double, minutesAhead: Int = 10): Status {
        if (value.isNullOrBlank()) return Status.UNKNOWN
        val rules = parse(value) ?: return Status.UNKNOWN
        val now = isOpen(rules, at, lat, lng) ?: return Status.UNKNOWN
        if (!now) return Status.CLOSED
        if (minutesAhead > 0) {
            val later = (at.clone() as Calendar).apply { add(Calendar.MINUTE, minutesAhead) }
            val stillOpen = isOpen(rules, later, lat, lng) ?: return Status.OPEN
            if (!stillOpen) return Status.CLOSED
        }
        return Status.OPEN
    }

    // ---------------------------------------------------------------- model

    private sealed interface TimePoint {
        data class Fixed(val minute: Int) : TimePoint
        data class Sun(val event: String, val offsetMin: Int) : TimePoint
    }

    private data class Span(val start: TimePoint, val end: TimePoint?)

    private data class Interval(val start: Int, val end: Int)

    private data class Rule(
        val months: Set<Int>?,
        val weekdays: Set<Int>?,
        /** null = closed ("off"). */
        val spans: List<Span>?,
        val additive: Boolean,
    ) {
        fun matches(day: Day): Boolean =
            (months == null || day.month in months) && (weekdays == null || day.weekday in weekdays)
    }

    // ---------------------------------------------------------------- evaluation

    private fun isOpen(rules: List<Rule>, at: Calendar, lat: Double, lng: Double): Boolean? {
        val minute = at.get(Calendar.HOUR_OF_DAY) * 60 + at.get(Calendar.MINUTE)
        val today = intervalsFor(rules, dayOf(at), lat, lng) ?: return null
        if (today.any { minute >= it.start && minute < it.end }) return true

        val yesterdayCal = (at.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
        val yesterday = intervalsFor(rules, dayOf(yesterdayCal), lat, lng) ?: return null
        val shifted = minute + 1440
        return yesterday.any { it.end > 1440 && shifted >= it.start && shifted < it.end }
    }

    private fun intervalsFor(rules: List<Rule>, day: Day, lat: Double, lng: Double): List<Interval>? {
        var result = emptyList<Interval>() // no matching rule = closed
        for (rule in rules) {
            if (!rule.matches(day)) continue
            val resolved = rule.spans?.map { span ->
                val s = resolve(span.start, day, lat, lng) ?: return null
                var e = span.end?.let { resolve(it, day, lat, lng) ?: return null } ?: 1440
                if (e <= s) e += 1440
                Interval(s, e)
            } ?: emptyList()
            result = if (rule.additive) result + resolved else resolved
        }
        return result
    }

    private fun resolve(point: TimePoint, day: Day, lat: Double, lng: Double): Int? = when (point) {
        is TimePoint.Fixed -> point.minute
        is TimePoint.Sun -> SunTimes.minuteOfDay(point.event, day, lat, lng)?.plus(point.offsetMin)
    }

    fun dayOf(cal: Calendar): Day {
        val dow = cal.get(Calendar.DAY_OF_WEEK) // Sunday = 1
        return Day(
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            dayOfMonth = cal.get(Calendar.DAY_OF_MONTH),
            weekday = (dow + 5) % 7,
            dayOfYear = cal.get(Calendar.DAY_OF_YEAR),
            utcOffsetMin = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 60_000,
        )
    }

    // ---------------------------------------------------------------- parsing

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val DAYS = listOf("mo", "tu", "we", "th", "fr", "sa", "su")
    private val HOLIDAYS = setOf("ph", "sh")

    private const val M = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)"
    private const val D = "(?:mo|tu|we|th|fr|sa|su|ph|sh)"
    private val monthSelector = Regex("""^($M(?:\s*-\s*$M)?(?:\s*,\s*$M(?:\s*-\s*$M)?)*)\s*:?(?=\s|$)""", RegexOption.IGNORE_CASE)
    private val daySelector = Regex("""^($D(?:\s*-\s*$D)?(?:\s*,\s*$D(?:\s*-\s*$D)?)*)\s*:?(?=\s|$)""", RegexOption.IGNORE_CASE)

    private const val SUN = "(?:sunrise|sunset|dawn|dusk)"
    private const val TIME = """(?:\d{1,2}:\d{2}|$SUN|\(\s*$SUN\s*[+-]\s*\d{1,2}:\d{2}\s*\))"""
    private val spanRegex = Regex("""^($TIME)\s*(?:-\s*($TIME)|(\+))""", RegexOption.IGNORE_CASE)
    private val sunOffset = Regex("""\(\s*($SUN)\s*([+-])\s*(\d{1,2}):(\d{2})\s*\)""", RegexOption.IGNORE_CASE)

    /** Splits "Mo-Fr 08:00-17:00, Sa 09:00-12:00" into an override rule plus an additive rule. */
    private val additiveSplit = Regex(""",\s+(?=$D\b|$M\b)""", RegexOption.IGNORE_CASE)
    private val endsWithTimeOrState = Regex("""(\d|\)|\+|off|closed|open)\s*$""", RegexOption.IGNORE_CASE)

    /** Returns null if any part of [value] is outside the supported subset. */
    private fun parse(value: String): List<Rule>? {
        val rules = mutableListOf<Rule>()
        val pieces = value.replace("||", ";").split(';').map { it.trim() }.filter { it.isNotEmpty() }
        for (piece in pieces) {
            for ((index, part) in splitAdditive(piece).withIndex()) {
                val rule = parseRule(part, additive = index > 0) ?: return null
                if (rule !== SKIP) rules += rule
            }
        }
        return rules
    }

    private fun splitAdditive(piece: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        for (match in additiveSplit.findAll(piece)) {
            val left = piece.substring(start, match.range.first)
            if (endsWithTimeOrState.containsMatchIn(left)) {
                parts += left.trim()
                start = match.range.last + 1
            }
        }
        parts += piece.substring(start).trim()
        return parts
    }

    private val SKIP = Rule(null, null, null, false)

    private fun parseRule(raw: String, additive: Boolean): Rule? {
        var rest = raw.replace(Regex("\"[^\"]*\""), "").trim() // drop comments
        if (rest.equals("24/7", ignoreCase = true)) {
            return Rule(null, null, listOf(Span(TimePoint.Fixed(0), TimePoint.Fixed(1440))), additive)
        }

        var months: Set<Int>? = null
        monthSelector.find(rest)?.let {
            months = expand(it.groupValues[1], MONTHS, offset = 1) ?: return null
            rest = rest.substring(it.range.last + 1).trim()
        }

        var weekdays: Set<Int>? = null
        daySelector.find(rest)?.let { match ->
            val tokens = match.groupValues[1].lowercase()
            val withoutHolidays = tokens.split(',').map { it.trim() }.filter { it !in HOLIDAYS }
            if (withoutHolidays.isEmpty()) return SKIP // a PH/SH-only rule: unknowable, ignore it
            weekdays = expand(withoutHolidays.joinToString(","), DAYS, offset = 0) ?: return null
            rest = rest.substring(match.range.last + 1).trim()
        }

        val lower = rest.lowercase()
        if (lower == "off" || lower == "closed") return Rule(months, weekdays, null, additive)
        if (lower == "24/7") return Rule(months, weekdays, listOf(Span(TimePoint.Fixed(0), TimePoint.Fixed(1440))), additive)
        if (lower.isEmpty() || lower == "open") {
            // A selector with no times means open all day; a bare empty rule is meaningless.
            if (months == null && weekdays == null) return null
            return Rule(months, weekdays, listOf(Span(TimePoint.Fixed(0), TimePoint.Fixed(1440))), additive)
        }

        val spans = mutableListOf<Span>()
        while (rest.isNotEmpty()) {
            val m = spanRegex.find(rest) ?: break
            val start = parsePoint(m.groupValues[1]) ?: return null
            val end = if (m.groupValues[3] == "+") null else parsePoint(m.groupValues[2]) ?: return null
            spans += Span(start, end)
            rest = rest.substring(m.range.last + 1).trim().removePrefix(",").trim()
        }
        if (spans.isEmpty()) return null
        if (rest.isNotEmpty() && !rest.equals("open", ignoreCase = true)) return null
        return Rule(months, weekdays, spans, additive)
    }

    private fun parsePoint(text: String): TimePoint? {
        val t = text.trim().lowercase()
        if (t in setOf("sunrise", "sunset", "dawn", "dusk")) return TimePoint.Sun(t, 0)
        sunOffset.matchEntire(t)?.let {
            val sign = if (it.groupValues[2] == "-") -1 else 1
            val off = it.groupValues[3].toInt() * 60 + it.groupValues[4].toInt()
            return TimePoint.Sun(it.groupValues[1].lowercase(), sign * off)
        }
        val parts = t.split(':')
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h > 48 || m > 59) return null
        return TimePoint.Fixed(h * 60 + m)
    }

    /** Expands "mo-we,fr" style lists (with wrap-around ranges) into indices. */
    private fun expand(list: String, names: List<String>, offset: Int): Set<Int>? {
        val out = mutableSetOf<Int>()
        for (part in list.lowercase().split(',').map { it.trim() }) {
            val ends = part.split('-').map { names.indexOf(it.trim()) }
            if (ends.any { it < 0 }) return null
            when (ends.size) {
                1 -> out += ends[0] + offset
                2 -> {
                    var i = ends[0]
                    while (true) {
                        out += i + offset
                        if (i == ends[1]) break
                        i = (i + 1) % names.size
                    }
                }
                else -> return null
            }
        }
        return out
    }
}

/**
 * Sunrise / sunset / civil dawn / dusk using the standard almanac algorithm
 * (accurate to a few minutes, which is plenty for park toilet gates).
 */
object SunTimes {
    private const val OFFICIAL_ZENITH = 90.833
    private const val CIVIL_ZENITH = 96.0

    /** Local minute of day for the event, or null in polar day/night. */
    fun minuteOfDay(event: String, day: OpeningHours.Day, lat: Double, lng: Double): Int? {
        val rising = event == "sunrise" || event == "dawn"
        val zenith = if (event == "dawn" || event == "dusk") CIVIL_ZENITH else OFFICIAL_ZENITH

        val lngHour = lng / 15.0
        val t = day.dayOfYear + ((if (rising) 6.0 else 18.0) - lngHour) / 24.0
        val meanAnomaly = 0.9856 * t - 3.289
        val trueLng = norm360(
            meanAnomaly + 1.916 * sinDeg(meanAnomaly) + 0.020 * sinDeg(2 * meanAnomaly) + 282.634,
        )
        var ra = norm360(Math.toDegrees(atan(0.91764 * tanDeg(trueLng))))
        ra += floor(trueLng / 90.0) * 90.0 - floor(ra / 90.0) * 90.0
        ra /= 15.0

        val sinDec = 0.39782 * sinDeg(trueLng)
        val cosDec = cos(asin(sinDec))
        val cosH = (cosDeg(zenith) - sinDec * sinDeg(lat)) / (cosDec * cosDeg(lat))
        if (cosH > 1 || cosH < -1) return null

        val h = (if (rising) 360.0 - Math.toDegrees(acos(cosH)) else Math.toDegrees(acos(cosH))) / 15.0
        val localMean = h + ra - 0.06571 * t - 6.622
        val ut = ((localMean - lngHour) % 24 + 24) % 24
        val local = ((ut * 60 + day.utcOffsetMin) % 1440 + 1440) % 1440
        return local.roundToInt()
    }

    private fun norm360(d: Double) = ((d % 360) + 360) % 360
    private fun sinDeg(d: Double) = sin(Math.toRadians(d))
    private fun cosDeg(d: Double) = cos(Math.toRadians(d))
    private fun tanDeg(d: Double) = tan(Math.toRadians(d))
}
