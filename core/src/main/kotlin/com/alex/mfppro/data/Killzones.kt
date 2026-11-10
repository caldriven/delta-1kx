package com.alex.mfppro.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.IsoFields

/**
 * Trading sessions, ICT killzones and period opens, in each city's own time (so daylight saving is
 * right on both sides of the Atlantic).
 *
 * Colour language shared by both apps: cool colours are TIME (sessions and killzones), warm colours
 * are PRICE (opens and the previous day's range).
 */
object Killzones {
    val NY: ZoneId = ZoneId.of("America/New_York")
    val LONDON: ZoneId = ZoneId.of("Europe/London")
    val TOKYO: ZoneId = ZoneId.of("Asia/Tokyo")

    /** A daily window [startMin, endMin) in [zone] (end may pass midnight). [weekdays]: Mon–Fri only. */
    data class Window(val id: String, val name: String, val short: String, val zone: ZoneId, val startMin: Int, val endMin: Int, val weekdays: Boolean, val rgb: Long) {
        val lengthMin get() = ((endMin - startMin) + 1440) % 1440 + if (endMin == startMin) 1440 else 0
    }

    val KILLZONES = listOf(
        Window("asia", "Asia killzone", "Asia KZ", NY, 20 * 60, 0, weekdays = false, rgb = 0xFF7986CB),
        Window("london", "London killzone", "London KZ", LONDON, 7 * 60, 10 * 60, weekdays = false, rgb = 0xFF4FC3F7),
        Window("nyam", "New York AM killzone", "NY AM KZ", NY, 7 * 60, 10 * 60, weekdays = false, rgb = 0xFF4DB6AC),
        Window("ldnclose", "London close killzone", "LDN close KZ", NY, 10 * 60, 12 * 60, weekdays = false, rgb = 0xFFAED581),
    )
    val MARKETS = listOf(
        Window("tokyo", "Tokyo", "Tokyo", TOKYO, 9 * 60, 15 * 60, weekdays = true, rgb = 0xFF7986CB),
        Window("londonmkt", "London", "London", LONDON, 8 * 60, 16 * 60 + 30, weekdays = true, rgb = 0xFF4FC3F7),
        Window("nymkt", "New York", "New York", NY, 9 * 60 + 30, 16 * 60, weekdays = true, rgb = 0xFF4DB6AC),
    )

    /** Start of the occurrence of [w] that contains [t] or began most recently before it. */
    fun startOf(w: Window, t: Long): Long {
        val z = Instant.ofEpochMilli(t).atZone(w.zone)
        var st = z.toLocalDate().atStartOfDay(w.zone).plusMinutes(w.startMin.toLong())
        if (st.toInstant().toEpochMilli() > t) st = st.minusDays(1)
        return st.toInstant().toEpochMilli()
    }

    private fun weekend(t: Long, zone: ZoneId) = Instant.ofEpochMilli(t).atZone(zone).dayOfWeek.let { it == DayOfWeek.SATURDAY || it == DayOfWeek.SUNDAY }

    /** 0..1 through the window at [t], or null outside it. */
    fun progress(w: Window, t: Long): Double? {
        val st = startOf(w, t)
        val len = w.lengthMin * 60_000L
        if (t >= st + len) return null
        if (w.weekdays && weekend(st, w.zone)) return null
        return (t - st).toDouble() / len
    }

    /** The window now running, or the next one: (start, end, running). */
    data class Next(val window: Window, val start: Long, val end: Long, val running: Boolean) {
        fun msToEvent(now: Long) = if (running) end - now else start - now
    }

    fun next(w: Window, now: Long): Next {
        val len = w.lengthMin * 60_000L
        var st = startOf(w, now)
        if (now >= st + len || (w.weekdays && weekend(st, w.zone))) {
            st = Instant.ofEpochMilli(st).atZone(w.zone).plusDays(1).toInstant().toEpochMilli()
            var guard = 0
            while (w.weekdays && weekend(st, w.zone) && guard++ < 7) st = Instant.ofEpochMilli(st).atZone(w.zone).plusDays(1).toInstant().toEpochMilli()
        }
        return Next(w, st, st + len, now in st until st + len)
    }

    // ---- price levels ------------------------------------------------------------------------

    enum class Kind(val short: String, val rgb: Long) {
        DAY("D open", 0xFFFFCA28), WEEK("W open", 0xFFFF8A65), MONTH("M open", 0xFFF06292),
        NY_MIDNIGHT("NY mid", 0xFFBA68C8), PDH("PDH", 0xFF9E9E9E), PDL("PDL", 0xFF9E9E9E),
    }

    /** A level living from [start] to [end] (the end of its period; the current one runs to now). */
    data class Level(val kind: Kind, val price: Double, val start: Long, val end: Long, val current: Boolean)

    /**
     * Daily, weekly and monthly opens (midnight in [tz]), the New York midnight open, and the previous
     * day's high and low, for every period the bars cover. [days] (daily candles) fills in weekly and
     * monthly opens that began before the first intraday bar.
     */
    fun levels(bars: List<Candle>, days: List<Candle> = emptyList(), tz: ZoneId = ZoneOffset.UTC, now: Long = System.currentTimeMillis()): List<Level> {
        if (bars.isEmpty()) return emptyList()
        val out = ArrayList<Level>()
        fun periods(kind: Kind, zone: ZoneId, key: (LocalDate) -> Long) {
            var cur: Long? = null; var open = 0.0; var start = 0L
            for (b in bars) {
                val k = key(Instant.ofEpochMilli(b.openTime).atZone(zone).toLocalDate())
                if (k != cur) {
                    if (cur != null) out += Level(kind, open, start, b.openTime, false)
                    cur = k; open = b.o; start = b.openTime
                }
            }
            if (cur != null) out += Level(kind, open, start, now, true)
        }
        periods(Kind.DAY, tz) { it.toEpochDay() }
        periods(Kind.NY_MIDNIGHT, NY) { it.toEpochDay() }
        periods(Kind.WEEK, tz) { it.get(IsoFields.WEEK_BASED_YEAR) * 100L + it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) }
        periods(Kind.MONTH, tz) { it.year * 100L + it.monthValue }
        // The first week and month usually began before the bars: take their real open from the dailies.
        for (kind in listOf(Kind.WEEK, Kind.MONTH)) {
            val first = out.firstOrNull { it.kind == kind } ?: continue
            val d0 = Instant.ofEpochMilli(first.start).atZone(tz).toLocalDate()
            val pStart = (if (kind == Kind.WEEK) d0.with(DayOfWeek.MONDAY) else d0.withDayOfMonth(1)).atStartOfDay(tz).toInstant().toEpochMilli()
            val real = days.firstOrNull { it.openTime >= pStart }?.takeIf { it.openTime <= first.start }?.o
            if (real != null) out[out.indexOf(first)] = first.copy(price = real)
        }
        // Previous day high/low, drawn across the day that follows it.
        val byDay = bars.groupBy { Instant.ofEpochMilli(it.openTime).atZone(tz).toLocalDate().toEpochDay() }.toSortedMap()
        val keys = byDay.keys.toList()
        for (i in 1 until keys.size) {
            val prev = byDay.getValue(keys[i - 1]); val day = byDay.getValue(keys[i])
            // A partial first day (history starting mid-day) would give a false range.
            if (i == 1 && prev.first().openTime > LocalDate.ofEpochDay(keys[0]).atStartOfDay(tz).toInstant().toEpochMilli() + 3_600_000) continue
            val start = day.first().openTime
            val end = if (i == keys.size - 1) now else byDay.getValue(keys[i + 1]).first().openTime
            val cur = i == keys.size - 1
            out += Level(Kind.PDH, prev.maxOf { it.h }, start, end, cur)
            out += Level(Kind.PDL, prev.minOf { it.l }, start, end, cur)
        }
        return out
    }

    /** The current level of each kind, nearest to [price] first: the "levels" readout. */
    fun nearest(levels: List<Level>, price: Double): List<Level> =
        levels.filter { it.current }.sortedBy { kotlin.math.abs(it.price - price) }
}
