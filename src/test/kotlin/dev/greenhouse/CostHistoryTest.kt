package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CostHistoryTest {
    private val day = "2026-10-28"

    private class CostFixture(var profile: ProfileData = ProfileData()) {
        private val sessions = TrackerSessions()
        private val days = TrackerDays()
        private val scope = "server/player/apple"
        val placements get() = profile.costs.placements

        fun plant(cell: String, id: String, price: Double?, day: String, now: Long = 0) {
            profile.costs.plant(cell, id, price, day, now).forEach(::record)
        }

        fun find(cell: String, id: String) = profile.costs.find(cell, id)
        fun returned(token: String) { profile.costs.returned(token)?.let(::record) }
        fun pickedUp(token: String) { profile.costs.pickedUp(token)?.let(::record) }
        fun removed(token: String, now: Long) { profile.costs.removed(token, now)?.let(::record) }
        fun expire(now: Long) = profile.costs.expire(now)

        private fun record(change: CostChange) {
            val placement = change.placement
            val date = java.time.LocalDate.parse(placement.day)
            if (change.refund) {
                profile.cost.refund(placement.price, placement.allGeneration)
                days.refundCost(profile, date, placement.price, placement.dayGeneration)
                sessions.refundCost(scope, placement.price, placement.sessionGeneration)
            } else {
                placement.allGeneration = profile.cost.charge(placement.price)
                placement.dayGeneration = days.recordCost(profile, date, placement.price)
                placement.sessionGeneration = sessions.recordCost(scope, placement.price)
            }
        }

        fun view(mode: TrackerMode, day: String): TrackerTotals = when (mode) {
            TrackerMode.SESSION -> sessions.view(scope)
            TrackerMode.DAILY -> days.view(profile, java.time.LocalDate.parse(day))
            TrackerMode.ALL_TIME -> TrackerTotals(profile.items, profile.activeMillis,
                cost = profile.cost.value, unpricedCosts = profile.cost.unpriced)
        }

        fun cost(mode: TrackerMode = TrackerMode.ALL_TIME, date: String = "2026-10-28") = view(mode, date).cost

        fun reset(mode: TrackerMode, date: String) {
            when (mode) {
                TrackerMode.SESSION -> sessions.reset(scope)
                TrackerMode.DAILY -> days.reset(profile, java.time.LocalDate.parse(date))
                TrackerMode.ALL_TIME -> profile.cost = CostTotal()
            }
        }

        fun reconnect() = CostFixture(Json.gson.fromJson(Json.gson.toJson(profile), ProfileData::class.java))
    }

    @Test fun `placement charges every view and only a confirmed return refunds it`() {
        val costs = CostFixture()
        costs.plant("4:0:0", "BLASTBERRY", 100.0, day)
        TrackerMode.entries.forEach { assertEquals(100.0, costs.cost(it)) }
        val plant = costs.find("4:0:0", "BLASTBERRY")!!
        costs.removed(plant.token, 0)
        assertEquals(100.0, costs.cost())
        costs.returned(plant.token)
        TrackerMode.entries.forEach { assertEquals(0.0, costs.cost(it)) }
        costs.returned(plant.token)
        assertEquals(0.0, costs.cost())
    }

    @Test fun `item arriving before model removal waits for removal`() {
        val costs = CostFixture()
        costs.plant("a", "BLASTBERRY", 100.0, day)
        val token = costs.find("a", "BLASTBERRY")!!.token
        costs.returned(token)
        assertEquals(100.0, costs.cost())
        costs.removed(token, 10)
        assertEquals(0.0, costs.cost())
    }

    @Test fun `no return leaves the original cost after timeout`() {
        val costs = CostFixture()
        costs.plant("a", "BLASTBERRY", 100.0, day)
        val token = costs.find("a", "BLASTBERRY")!!.token
        costs.removed(token, 0)
        costs.expire(HarvestTiming.RETURN_CREDIT_MILLIS + 1)
        costs.returned(token)
        assertEquals(100.0, costs.cost())
        assertTrue(costs.placements.isEmpty())
    }

    @Test fun `refund reverses original day and frozen price across a reconnect`() {
        val original = CostFixture()
        original.plant("a", "BLASTBERRY", 100.0, day)
        val costs = original.reconnect()
        costs.plant("b", "BLASTBERRY", 250.0, "2026-10-29")
        val token = costs.find("a", "BLASTBERRY")!!.token
        costs.removed(token, 0)
        costs.returned(token)
        assertEquals(250.0, costs.cost())
        assertEquals(0.0, costs.cost(TrackerMode.DAILY))
        assertEquals(250.0, costs.cost(TrackerMode.DAILY, "2026-10-29"))
        assertEquals(250.0, costs.cost(TrackerMode.SESSION))
    }

    @Test fun `resetting one view cannot refund charges from before its reset`() {
        TrackerMode.entries.forEach { resetMode ->
            val costs = CostFixture()
            costs.plant("a", "BLASTBERRY", 100.0, day)
            val token = costs.find("a", "BLASTBERRY")!!.token
            costs.reset(resetMode, day)
            costs.plant("b", "BLASTBERRY", 250.0, day)
            costs.removed(token, 0)
            costs.returned(token)
            TrackerMode.entries.forEach { assertEquals(250.0, costs.cost(it), "$resetMode reset, $it view") }
        }
    }

    @Test fun `unpriced placements stay visible and refunds clear them`() {
        val costs = CostFixture()
        costs.plant("a", "BLASTBERRY", null, day)
        assertEquals(1, costs.view(TrackerMode.ALL_TIME, day).unpricedCosts)
        val token = costs.find("a", "BLASTBERRY")!!.token
        costs.removed(token, 0)
        costs.returned(token)
        assertEquals(0, costs.view(TrackerMode.ALL_TIME, day).unpricedCosts)
    }

    @Test fun `old profiles start without costs and saved placements survive loading`() {
        val profile = Json.gson.fromJson("{}", ProfileData::class.java)
        val costs = CostFixture(profile)
        assertEquals(0.0, costs.cost())
        costs.plant("a", "BLASTBERRY", 100.0, day)
        val restored = costs.reconnect()
        restored.profile.costs.validate()
        restored.profile.cost.validate()
        assertEquals(100.0, restored.cost())
        assertNotNull(restored.find("a", "BLASTBERRY"))
        assertEquals(0.0, restored.cost(TrackerMode.SESSION))
    }

    @Test fun `failed placement creates no cost and confirmed placement charges once`() {
        val costs = CostFixture()
        val history = PlacementHistory()
        val placed = mutableMapOf<String, String>()
        history.attempt("a", "BLASTBERRY", setOf(1), 0)
        fun confirm(models: Set<Int>, now: Long) = history.confirm(mapOf("a" to mapOf("BLASTBERRY" to models)), placed, now) { cell, id ->
            costs.plant(cell, id, 100.0, day)
        }
        confirm(setOf(1), 10)
        assertEquals(0.0, costs.cost())
        confirm(setOf(2), 20)
        confirm(setOf(2), 30)
        assertEquals(100.0, costs.cost())
    }

    @Test fun `inventory and delayed sack return refund once and count as no income`() {
        var now = 0L
        val costs = CostFixture()
        costs.plant("a", "BLASTBERRY", 100.0, day)
        val token = costs.find("a", "BLASTBERRY")!!.token
        val attribution = HarvestAttribution(clock = { now })
        attribution.snapshot(emptyMap(), true)
        attribution.harvested("BLASTBERRY")
        attribution.returned("BLASTBERRY", key = token) { costs.returned(token) }
        attribution.snapshot(mapOf("BLASTBERRY" to 1L), true)
        costs.removed(token, now)
        now = 10000
        attribution.sackReport(emptyMap(), true)
        attribution.sackReport(mapOf("BLASTBERRY" to 1L), true)
        assertTrue(attribution.flush().isEmpty())
        assertEquals(0.0, costs.cost())
    }

    @Test fun `gross sack additions expose a refund even when placement nets to zero`() {
        val hovers = listOf("Added\n+1 Blastberry (Sack)", "Removed\n-1 Blastberry (Sack)")
        val net = SackReport.parse(hovers) { if (it == "Blastberry") "BLASTBERRY" else null }
        val added = SackReport.added(hovers) { if (it == "Blastberry") "BLASTBERRY" else null }
        assertTrue(net.isEmpty())
        assertEquals(mapOf("BLASTBERRY" to 1L), added)
        var refunds = 0
        val attribution = HarvestAttribution(clock = { 0 })
        attribution.returned("BLASTBERRY") { refunds++ }
        attribution.sackReport(net, true, added)
        assertEquals(1, refunds)
        assertTrue(attribution.flush().isEmpty())
    }

    @Test fun `same type returns match quantities and unrelated reports preserve pending refunds`() {
        val attribution = HarvestAttribution(clock = { 0 })
        var first = 0
        var second = 0
        attribution.returned("BLASTBERRY", key = "a") { first++ }
        attribution.returned("BLASTBERRY", key = "a") { fail("duplicate return watch") }
        attribution.returned("BLASTBERRY", key = "b") { second++ }
        attribution.sackReport(mapOf("WHEAT" to 1L), true)
        attribution.sackReport(mapOf("BLASTBERRY" to 1L), true)
        assertEquals(1, first)
        assertEquals(0, second)
        attribution.sackReport(mapOf("BLASTBERRY" to 1L), true)
        assertEquals(1, second)
    }

    @Test fun `combined planting refund and real harvest does not subtract the refund twice`() {
        val attribution = HarvestAttribution(clock = { 0 })
        var refunds = 0
        attribution.harvested("BLASTBERRY")
        attribution.returned("BLASTBERRY") { refunds++ }
        // Three added: one return and two harvests. One removed for planting.
        attribution.sackReport(mapOf("BLASTBERRY" to 2L), true, mapOf("BLASTBERRY" to 3L))
        assertEquals(1, refunds)
        assertEquals(mapOf("BLASTBERRY" to 2L), attribution.flush())
    }

    @Test fun `rapid pickup and replant preserves both early and delayed refunds`() {
        listOf(true, false).forEach { early ->
            val costs = CostFixture()
            costs.plant("a", "BLASTBERRY", 100.0, day, 0)
            val oldToken = costs.find("a", "BLASTBERRY")!!.token
            if (early) costs.returned(oldToken)
            costs.plant("a", "BLASTBERRY", 250.0, day, 10)
            if (!early) costs.returned(oldToken)
            assertEquals(250.0, costs.cost())
            assertEquals(250.0, costs.find("a", "BLASTBERRY")!!.price)
        }
    }

    @Test fun `waiting before pickup does not require model disappearance to refund a sack return`() {
        var now = 0L
        val costs = CostFixture()
        costs.plant("a", "MAGIC_JELLYBEAN", 21953.7, day, now)
        val token = costs.find("a", "MAGIC_JELLYBEAN")!!.token
        val attribution = HarvestAttribution(clock = { now })
        now = 5 * 60_000L
        costs.expire(now)
        assertNotNull(costs.profile.costs.at("a"))
        attribution.returned("MAGIC_JELLYBEAN", key = token) { costs.pickedUp(token) }
        now += 5_000L
        val hover = "Added items:\n+1 Magic Jellybean (Mutations Sack)"
        val additions = SackReport.added(listOf(hover)) { Catalog.aliases[it.lowercase()] }
        // The clicked model changed, or another piece remains, so no removal event arrives.
        attribution.sackReport(additions, true, additions)
        TrackerMode.entries.forEach { assertEquals(0.0, costs.cost(it)) }
        assertTrue(attribution.flush().isEmpty())
        assertTrue(costs.placements.isEmpty())
        attribution.sackReport(additions, true, additions)
        assertEquals(0.0, costs.cost())
    }

    @Test fun `pickup return refunds inventory without a separate model removal event`() {
        var now = 0L
        val costs = CostFixture()
        costs.plant("a", "MAGIC_JELLYBEAN", 100.0, day, now)
        val token = costs.find("a", "MAGIC_JELLYBEAN")!!.token
        val attribution = HarvestAttribution(clock = { now })
        attribution.snapshot(emptyMap(), true)
        now = 10_000L
        attribution.returned("MAGIC_JELLYBEAN", key = token) { costs.pickedUp(token) }
        attribution.snapshot(mapOf("MAGIC_JELLYBEAN" to 1L), true)
        assertEquals(0.0, costs.cost())
        assertTrue(attribution.flush().isEmpty())
    }

    @Test fun `waiting and clicking without any spawner return keeps its cost`() {
        var now = 0L
        val costs = CostFixture()
        costs.plant("a", "MAGIC_JELLYBEAN", 100.0, day, now)
        val token = costs.find("a", "MAGIC_JELLYBEAN")!!.token
        val attribution = HarvestAttribution(clock = { now })
        now = 5 * 60_000L
        attribution.returned("MAGIC_JELLYBEAN", key = token) { costs.pickedUp(token) }
        now += HarvestTiming.RETURN_CREDIT_MILLIS + 1
        attribution.sackReport(emptyMap(), true)
        assertEquals(100.0, costs.cost())
    }

    @Test fun `logged Magic Jellybean sugar cane pickup registers a mutation return after waiting`() {
        var now = 47_343L
        val costs = CostFixture()
        val attribution = HarvestAttribution(clock = { now })
        costs.plant("a", "MAGIC_JELLYBEAN", 21953.7, day, now)
        val placement = costs.profile.costs.at("a")!!
        now = 49_676L
        // The real report hit not_a_crop_head, then authorized SUGAR_CANE here.
        val id = HarvestSignals.blockTarget(placement.id, isCropHead = false) {
            error("A planted crop block must use the recorded mutation identity")
        }!!
        assertEquals("MAGIC_JELLYBEAN", id)
        attribution.returned(id, key = placement.token) { costs.pickedUp(placement.token) }
        now = 55_267L
        val hover = "Added items:\n+1 Magic Jellybean (Mutations Sack)"
        val additions = SackReport.added(listOf(hover)) { Catalog.aliases[it.lowercase()] }
        attribution.sackReport(additions, true, additions)
        TrackerMode.entries.forEach { assertEquals(0.0, costs.cost(it)) }
        assertTrue(attribution.flush().isEmpty())
    }

    @Test fun `unplanted crop blocks do not create mutation return targets`() {
        var detected = false
        assertNull(HarvestSignals.blockTarget(null, isCropHead = false) {
            detected = true
            "MAGIC_JELLYBEAN"
        })
        assertFalse(detected)
        assertEquals("BLASTBERRY", HarvestSignals.blockTarget(null, isCropHead = true) { "BLASTBERRY" })
    }

    @Test fun `expired return watches cannot refund a later gain`() {
        var now = 0L
        var refunds = 0
        val attribution = HarvestAttribution(clock = { now })
        attribution.returned("BLASTBERRY") { refunds++ }
        now = HarvestTiming.RETURN_CREDIT_MILLIS + 1
        attribution.harvested("BLASTBERRY")
        attribution.sackReport(mapOf("BLASTBERRY" to 1L), true)
        assertEquals(0, refunds)
        assertEquals(mapOf("BLASTBERRY" to 1L), attribution.flush())
    }
}
