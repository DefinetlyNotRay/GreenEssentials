package dev.greenhouse

import java.time.Clock
import java.time.Instant
import java.time.LocalDate

internal class DailySourceValues {
    var values = mutableMapOf<String, Double>()
    var unpriced = mutableSetOf<String>()

    fun validate(items: Map<String, Long>) {
        requireNotNull(values)
        requireNotNull(unpriced)
        require(values.keys.all { it in items } && unpriced.all { it in items })
        require(values.values.all { it.isFinite() && it >= 0 })
    }
}

internal class DailyTrackerData {
    var items = mutableMapOf<String, Long>()
    var values = mutableMapOf<String, Double>()
    var unpriced = mutableSetOf<String>()
    var activeMillis = 0L
    var cost = CostTotal()
    var sources = mutableMapOf<PriceSource, DailySourceValues>()
    var countedForAllTime = false
    var allTimeHarvestPeriod: String? = null

    fun validate() {
        requireNotNull(cost).validate()
        requireNotNull(items)
        requireNotNull(values)
        requireNotNull(unpriced)
        requireNotNull(sources).forEach { (source, values) ->
            requireNotNull(source)
            requireNotNull(values).validate(items)
        }
        require(activeMillis >= 0 && items.values.all { it >= 0 })
        require(values.keys.all { it in items } && unpriced.all { it in items })
        require(values.values.all { it.isFinite() && it >= 0 })
    }
}

internal class TrackerDays(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val history: DailyHistoryStore? = null,
) {
    private fun read(profile: ProfileData, date: LocalDate): DailyTrackerData? =
        if (history != null) history.read(profile, date) else profile.days[date.toString()]

    private fun edit(profile: ProfileData, date: LocalDate, create: Boolean = true): DailyTrackerData? =
        if (history != null) history.edit(profile, date, create)
        else if (create) profile.days.getOrPut(date.toString()) { DailyTrackerData() }
        else profile.days[date.toString()]

    private class RangeView(val profile: ProfileData?, val range: TrackerDateRange, val source: PriceSource, val totals: DailyRangeTotals)
    private var rangeView: RangeView? = null

    private fun update(profile: ProfileData, date: LocalDate, create: Boolean = true, change: (DailyTrackerData) -> Unit): DailyTrackerData? {
        val cached = rangeView?.takeIf { it.profile === profile && date in it.range }
        val before = cached?.let { view(profile, date, it.source) }
        // Subtract before changing maps referenced by the old day's view.
        if (before != null) cached.totals.add(before, -1)
        return try {
            val day = edit(profile, date, create)
            if (day != null) change(day)
            if (cached != null) cached.totals.add(view(profile, date, cached.source))
            day
        } catch (error: Exception) {
            rangeView = null
            throw error
        }
    }

    fun viewRange(profile: ProfileData?, range: TrackerDateRange, source: PriceSource): TrackerTotals {
        val cached = rangeView
        if (cached != null && cached.profile === profile && cached.range == range && cached.source == source) return cached.totals.view()
        val aggregate = DailyRangeTotals()
        if (profile != null) {
            val records = if (history != null) history.between(profile, range)
                else profile.days.asSequence().map { LocalDate.parse(it.key) to it.value }.filter { it.first in range }
            for ((_, day) in records) aggregate.add(totals(day, source))
        }
        rangeView = RangeView(profile, range, source, aggregate)
        return aggregate.view()
    }

    /** Seed old profiles once; subsequent HUD reads use a constant-size saved counter. */
    fun allTimeHarvestDays(profile: ProfileData): Int {
        profile.allTimeHarvestDays?.let { return it }
        val range = TrackerDateRange(LocalDate.MIN, LocalDate.MAX)
        val records = if (history != null) history.between(profile, range).map { it.second }
            else profile.days.values.asSequence()
        val count = records.count {
            it.allTimeHarvestPeriod == profile.allTimeHarvestPeriod &&
                (it.countedForAllTime || it.items.values.any { amount -> amount > 0 })
        }
        profile.allTimeHarvestDays = count
        return count
    }

    fun resetAllTimeHarvestDays(profile: ProfileData) {
        profile.allTimeHarvestDays = 0
        profile.allTimeHarvestPeriod = java.util.UUID.randomUUID().toString()
    }

    var recordingDay: LocalDate = today()
        private set

    fun today(): LocalDate = LocalDate.now(clock)

    fun dateAt(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(clock.zone).toLocalDate()

    fun rollOver(now: Long, flushPreviousDay: () -> Unit) {
        val date = dateAt(now)
        if (date == recordingDay) return
        // Flush pending gains before new observations enter the next day's summary.
        flushPreviousDay()
        recordingDay = date
    }

    fun record(profile: ProfileData, date: LocalDate, gains: Map<String, Long>, price: (String) -> Double?) =
        recordPrices(profile, date, gains) { id, source -> if (source == PriceSource.AUTO) price(id) else null }

    fun recordPrices(profile: ProfileData, date: LocalDate, gains: Map<String, Long>, price: (String, PriceSource) -> Double?) {
        if (gains.none { it.value > 0 }) return
        update(profile, date) { day ->
            val samePeriod = day.allTimeHarvestPeriod == profile.allTimeHarvestPeriod
            val alreadyCounted = samePeriod && (day.countedForAllTime || day.items.values.any { it > 0 })
            if (!alreadyCounted) profile.allTimeHarvestDays = profile.allTimeHarvestDays?.plus(1)
            day.countedForAllTime = true
            day.allTimeHarvestPeriod = profile.allTimeHarvestPeriod
            // A legacy day has no historical quotes for the other sources; never backfill with today's prices.
            PriceSource.entries.filter { it != PriceSource.AUTO }.forEach { source ->
                day.sources.getOrPut(source) { DailySourceValues().apply { unpriced.addAll(day.items.keys) } }
            }
            for ((id, count) in gains) {
                if (count <= 0) continue
                day.items[id] = day.items.getOrDefault(id, 0) + count
                for (source in PriceSource.entries) {
                    val values = if (source == PriceSource.AUTO) day.values else day.sources.getValue(source).values
                    val unpriced = if (source == PriceSource.AUTO) day.unpriced else day.sources.getValue(source).unpriced
                    val value = price(id, source)?.times(count)?.takeIf { it.isFinite() && it >= 0 }
                    if (value == null) unpriced.add(id)
                    else values[id] = values.getOrDefault(id, 0.0) + value
                }
            }
        }
    }

    fun addTime(profile: ProfileData, fromMillis: Long, toMillis: Long) {
        var cursor = fromMillis
        while (cursor < toMillis) {
            val date = dateAt(cursor)
            val nextMidnight = date.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
            val end = minOf(toMillis, nextMidnight)
            update(profile, date) { it.activeMillis += end - cursor }
            cursor = end
        }
    }

    fun recordCost(profile: ProfileData, date: LocalDate, price: Double?): String {
        var generation = ""
        update(profile, date) { generation = it.cost.charge(price) }
        return generation
    }

    fun refundCost(profile: ProfileData, date: LocalDate, price: Double?, generation: String) {
        update(profile, date, create = false) { it.cost.refund(price, generation) }
    }

    fun view(profile: ProfileData?, date: LocalDate, source: PriceSource = PriceSource.AUTO): TrackerTotals {
        return totals(profile?.let { read(it, date) }, source)
    }

    private fun totals(day: DailyTrackerData?, source: PriceSource): TrackerTotals {
        val values = if (source == PriceSource.AUTO) day?.values.orEmpty() else day?.sources?.get(source)?.values.orEmpty()
        val unpriced = if (source == PriceSource.AUTO) day?.unpriced.orEmpty()
            else day?.sources?.get(source)?.unpriced.orEmpty() + (day?.items?.keys.orEmpty() - values.keys)
        return TrackerTotals(day?.items.orEmpty(), day?.activeMillis ?: 0, values, unpriced,
            cost = day?.cost?.value ?: 0.0, unpricedCosts = day?.cost?.unpriced ?: 0,
            harvestDays = if (day?.items?.values?.any { it > 0 } == true) 1 else 0)
    }

    fun reset(profile: ProfileData, date: LocalDate) {
        rangeView = null
        val old = read(profile, date)
        val counted = old?.let { it.countedForAllTime || it.items.values.any { count -> count > 0 } } == true
        val period = old?.allTimeHarvestPeriod
        if (history != null) history.reset(profile, date) else profile.days.remove(date.toString())
        // Keep only a tiny date marker so a daily reset cannot count the same all-time day twice.
        if (counted) edit(profile, date)?.let {
            it.countedForAllTime = true
            it.allTimeHarvestPeriod = period
        }
    }

    fun resetRange(profile: ProfileData, range: TrackerDateRange) {
        val dates = if (history != null) history.between(profile, range).map { it.first }.toList()
            else profile.days.keys.map(LocalDate::parse).filter { it in range }
        dates.forEach { reset(profile, it) }
        rangeView = null
    }

}
