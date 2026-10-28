package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TrackerSessionsTest {
    @Test fun `session totals accumulate quantities and farming time`() {
        val sessions = TrackerSessions()
        sessions.record("server/player/apple",mapOf("WHEAT" to 10L))
        sessions.record("server/player/apple",mapOf("WHEAT" to 5L,"CROPIE" to 1L))
        sessions.addTime("server/player/apple",1500)
        sessions.addTime("server/player/apple",500)
        assertEquals(TrackerTotals(mapOf("WHEAT" to 15L,"CROPIE" to 1L),2000),sessions.view("server/player/apple"))
    }
    @Test fun `changing profiles and returning retains separate sessions`() {
        val sessions = TrackerSessions()
        sessions.record("server/player/apple",mapOf("WHEAT" to 10L))
        sessions.record("server/player/banana",mapOf("WHEAT" to 99L))
        assertEquals(10L,sessions.view("server/player/apple").items["WHEAT"])
        assertEquals(99L,sessions.view("server/player/banana").items["WHEAT"])
        assertTrue(sessions.view("another-server/player/apple").items.isEmpty())
        assertTrue(sessions.view(null).items.isEmpty())
    }
    @Test fun `reset clears only the chosen profiles session and its duration`() {
        val sessions = TrackerSessions()
        sessions.record("apple",mapOf("WHEAT" to 10L)); sessions.addTime("apple",500)
        sessions.record("banana",mapOf("WHEAT" to 99L))
        sessions.reset("apple")
        assertEquals(TrackerTotals(emptyMap(),0),sessions.view("apple"))
        assertEquals(99L,sessions.view("banana").items["WHEAT"])
        sessions.record("apple",mapOf("WHEAT" to 2L))
        assertEquals(2L,sessions.view("apple").items["WHEAT"])
    }
    @Test fun `a new game run starts fresh session history`() {
        val firstRun = TrackerSessions()
        firstRun.record("apple",mapOf("WHEAT" to 100L))
        assertTrue(TrackerSessions().view("apple").items.isEmpty())
    }
    @Test fun `display mode is saved and legacy settings default to all time`() {
        val settings = Settings().apply { trackerMode = TrackerMode.SESSION }
        assertEquals(TrackerMode.SESSION,Json.gson.fromJson(Json.gson.toJson(settings),Settings::class.java).trackerMode)
        assertEquals(TrackerMode.ALL_TIME,Json.gson.fromJson("{}",Settings::class.java).trackerMode)
    }
    @Test fun `costs share the existing session scope lifetime and reset`() {
        val sessions = TrackerSessions()
        sessions.record("apple", mapOf("WHEAT" to 10L))
        val generation = sessions.recordCost("apple", 100.0)
        sessions.recordCost("banana", 250.0)
        assertEquals(10L, sessions.view("apple").items["WHEAT"])
        assertEquals(100.0, sessions.view("apple").cost)
        sessions.reset("apple")
        sessions.refundCost("apple", 100.0, generation)
        assertEquals(TrackerTotals(emptyMap(), 0), sessions.view("apple"))
        val nextGeneration = sessions.recordCost("apple", null)
        sessions.refundCost("apple", 100.0, generation)
        assertEquals(1, sessions.view("apple").unpricedCosts)
        sessions.refundCost("apple", null, nextGeneration)
        assertEquals(0, sessions.view("apple").unpricedCosts)
        assertEquals(250.0, sessions.view("banana").cost)
        assertEquals(0.0, TrackerSessions().view("banana").cost)
    }

}
