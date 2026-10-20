package dev.greenhouse

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class HarvestAttributionReplayTest {
    @TestFactory
    fun `event sequence replays`(): List<DynamicTest> =
        listOf("delayed-aloe", "blastberry-return", "expired-evidence", "screen-transition").map { name ->
            DynamicTest.dynamicTest(name) { replay(name) }
        }

    private fun replay(name: String) {
        val text = javaClass.getResourceAsStream("/replays/$name.json")!!.bufferedReader().use { it.readText() }
        val events = JsonParser.parseString(text).asJsonObject.getAsJsonArray("events")
        var now = 0L
        val journal = AttributionJournal()
        val attribution = HarvestAttribution(clock = { now }, record = journal::record)
        events.forEach { element ->
            val event = element.asJsonObject
            val next = event["at"].asLong
            assertTrue(next >= now, "Replay timestamps must be monotonic")
            now = next
            when (event["type"].asString) {
                "harvest" -> attribution.harvested(event["id"].asString)
                "return" -> attribution.returned(event["id"].asString)
                "inventory" -> attribution.snapshot(items(event, "items"), event["eligible"].asBoolean)
                "sack" -> attribution.sackReport(items(event, "items"), event["eligible"].asBoolean)
                "clear" -> attribution.clearEvidence(event["reason"].asString)
                "reset" -> attribution.reset(event["reason"].asString)
                "flush" -> assertEquals(items(event, "expected"), attribution.flush(), "$name at $now")
                else -> fail("Unknown replay event")
            }
        }
        assertTrue(journal.snapshot().isNotEmpty())
    }

    private fun items(event: JsonObject, key: String): Map<String, Long> =
        event.getAsJsonObject(key).entrySet().associate { it.key to it.value.asLong }

    @Test
    fun `diagnostics explain rejected gains and distinguish admitted candidates from credited totals`() {
        var now = 0L
        val journal = AttributionJournal()
        val attribution = HarvestAttribution(clock = { now }, record = journal::record)
        attribution.snapshot(emptyMap(), true)
        attribution.snapshot(mapOf("WHEAT" to 10L), true)
        attribution.harvested("WHEAT")
        now = 100
        attribution.snapshot(mapOf("WHEAT" to 20L), true)
        attribution.sackReport(mapOf("WHEAT" to 10L), true)
        assertEquals(mapOf("WHEAT" to 10L), attribution.flush())
        val events = journal.snapshot()
        assertTrue(events.any { it.item == "WHEAT" && it.reason == "NO_MATCHING_HARVEST" && it.admitted == 0L })
        assertEquals(20L, events.sumOf { it.admitted ?: 0 })
        assertEquals(10L, events.sumOf { it.credited ?: 0 })
        assertEquals(100L, events.last().elapsedMillis)
    }

    @Test
    fun `reset drops stale harvest evidence returns and inventory baseline`() {
        var now = 0L
        val attribution = HarvestAttribution(clock = { now })
        attribution.harvested("ALL_IN_ALOE")
        attribution.returned("BLASTBERRY")
        attribution.reset("world_changed")
        now = 100
        attribution.snapshot(mapOf("ALL_IN_ALOE" to 4L), true)
        attribution.sackReport(mapOf("ALL_IN_ALOE" to 4L), true)
        assertTrue(attribution.flush().isEmpty())
        assertFalse(attribution.active())
    }

    @Test
    fun `journal capacity is bounded and exported snapshots cannot mutate history`() {
        val journal = AttributionJournal(capacity = 2)
        journal.record(AttributionEvent(0, "first"))
        val snapshot = journal.snapshot()
        journal.record(AttributionEvent(1, "second"))
        journal.record(AttributionEvent(2, "third"))
        assertEquals(listOf("second", "third"), journal.snapshot().map { it.event })
        assertEquals(listOf("first"), snapshot.map { it.event })
        assertEquals(1L, journal.droppedEvents)
        assertThrows(IllegalArgumentException::class.java) { AttributionJournal(0) }
    }
}
