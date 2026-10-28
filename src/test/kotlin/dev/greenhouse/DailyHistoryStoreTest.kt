package dev.greenhouse

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DailyHistoryStoreTest {
    @TempDir lateinit var directory: Path
    private val date = LocalDate.of(2026, 10, 28)

    private fun files(): List<Path> = Files.walk(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json.gz") }.toList()
    }

    @Test fun `inline history migrates before removal and retains every source and cost`() {
        val profile = ProfileData()
        val days = TrackerDays()
        days.recordPrices(profile, date, mapOf("WHEAT" to 10L)) { _, source -> source.ordinal + 2.0 }
        days.recordCost(profile, date, 100.0)
        days.record(profile, date.minusMonths(1), mapOf("WHEAT" to 5L)) { 8.0 }
        val originalFile = directory.resolve("data.json")
        Files.writeString(originalFile, Json.gson.toJson(Settings().apply { profiles["apple"] = profile }))
        val originalBytes = Files.readAllBytes(originalFile)
        val history = DailyHistoryStore(directory.resolve("history"))
        history.register("apple", profile)
        assertTrue(profile.days.isEmpty())
        assertEquals(2, files().size)
        // Register/migrate does not remove the original settings file itself.
        assertArrayEquals(originalBytes, Files.readAllBytes(originalFile))
        val restored = ProfileData()
        val reopened = DailyHistoryStore(directory.resolve("history"))
        reopened.register("apple", restored)
        val archivedDays = TrackerDays(history = reopened)
        PriceSource.entries.forEach { source ->
            val totals = archivedDays.view(restored, date, source)
            assertEquals(10L, totals.items["WHEAT"])
            assertEquals((source.ordinal + 2.0) * 10, totals.value { error("Use stored prices") })
            assertEquals(100.0, totals.cost)
        }
        assertEquals(5L, archivedDays.view(restored, date.minusMonths(1)).items["WHEAT"])
    }

    @Test fun `main settings omit daily history while retaining all time totals and controls`() {
        val settings = Settings().apply {
            priceSource = PriceSource.NPC
            profiles["apple"] = ProfileData().apply {
                items["WHEAT"] = 42
                days[date.toString()] = DailyTrackerData().apply { items["WHEAT"] = 100 }
            }
        }
        val encoded = com.google.gson.JsonParser.parseString(StorageJson.encode(settings)).asJsonObject
        assertFalse(encoded.getAsJsonObject("profiles").getAsJsonObject("apple").has("days"))
        val restored = Json.gson.fromJson(encoded, Settings::class.java)
        assertEquals(42L, restored.profiles.getValue("apple").items["WHEAT"])
        assertEquals(PriceSource.NPC, restored.priceSource)
    }

    @Test fun `reading years of history keeps a bounded cache and flushes evicted changes`() {
        val profile = ProfileData()
        val history = DailyHistoryStore(directory, cacheLimit = 2)
        history.register("apple", profile)
        val days = TrackerDays(history = history)
        repeat(120) { index ->
            val day = date.minusMonths(index.toLong())
            days.record(profile, day, mapOf("WHEAT" to index + 1L)) { 2.0 }
            assertTrue(history.cachedMonthCount <= 2)
        }
        assertTrue(history.flush())
        assertEquals(120, files().size)
        repeat(120) { index ->
            assertEquals(index + 1L, days.view(profile, date.minusMonths(index.toLong())).items["WHEAT"])
            assertTrue(history.cachedMonthCount <= 2)
        }
    }

    @Test fun `startup and unrelated months do not read old corrupt archives`() {
        val history = DailyHistoryStore(directory)
        val profile = ProfileData()
        history.register("apple", profile)
        val days = TrackerDays(history = history)
        days.record(profile, date.minusYears(10), mapOf("WHEAT" to 1L)) { 2.0 }
        history.flush()
        val oldFile = files().single()
        Files.writeString(oldFile, "broken gzip")
        val failures = mutableListOf<Path>()
        val reopened = DailyHistoryStore(directory, onFailure = { path, _ -> failures.add(path) })
        val restored = ProfileData()
        reopened.register("apple", restored)
        assertEquals(0, reopened.cachedMonthCount)
        assertTrue(failures.isEmpty())
        val archivedDays = TrackerDays(history = reopened)
        archivedDays.record(restored, date, mapOf("WHEAT" to 2L)) { 3.0 }
        assertTrue(failures.isEmpty())
        assertTrue(archivedDays.view(restored, date.minusYears(10)).items.isEmpty())
        assertEquals(listOf(oldFile), failures)
        archivedDays.record(restored, date.minusYears(10), mapOf("WHEAT" to 999L)) { 999.0 }
        assertTrue(reopened.flush())
        assertEquals("broken gzip", Files.readString(oldFile))
        assertEquals(2L, archivedDays.view(restored, date).items["WHEAT"])
    }

    @Test fun `idle saves and reading old months never rewrite archive files`() {
        val profile = ProfileData()
        val history = DailyHistoryStore(directory)
        history.register("apple", profile)
        val days = TrackerDays(history = history)
        days.record(profile, date, mapOf("WHEAT" to 1L)) { 2.0 }
        assertTrue(history.flush())
        val file = files().single()
        val timestamp = java.nio.file.attribute.FileTime.fromMillis(1_000)
        Files.setLastModifiedTime(file, timestamp)
        val bytes = Files.readAllBytes(file)
        days.view(profile, date)
        assertTrue(history.flush())
        assertEquals(timestamp, Files.getLastModifiedTime(file))
        assertArrayEquals(bytes, Files.readAllBytes(file))
        days.record(profile, date.plusMonths(1), mapOf("WHEAT" to 2L)) { 3.0 }
        assertTrue(history.flush())
        assertEquals(timestamp, Files.getLastModifiedTime(file))
    }

    @Test fun `historical refund updates only its original month and a reset cannot be refunded again`() {
        val history = DailyHistoryStore(directory, cacheLimit = 1)
        val profile = ProfileData()
        history.register("apple", profile)
        val days = TrackerDays(history = history)
        val old = date.minusYears(5)
        val receipt = days.recordCost(profile, old, 100.0)
        days.recordCost(profile, date, 250.0)
        days.refundCost(profile, old, 100.0, receipt)
        assertEquals(0.0, days.view(profile, old).cost)
        assertEquals(250.0, days.view(profile, date).cost)
        days.reset(profile, old)
        days.refundCost(profile, old, 100.0, receipt)
        assertTrue(days.view(profile, old).items.isEmpty())
        assertEquals(0.0, days.view(profile, old).cost)
        days.recordCost(profile, old, 300.0)
        days.refundCost(profile, old, 100.0, receipt)
        assertEquals(300.0, days.view(profile, old).cost)
        history.flush()
        val reopened = DailyHistoryStore(directory)
        val restored = ProfileData()
        reopened.register("apple", restored)
        assertEquals(300.0, TrackerDays(history = reopened).view(restored, old).cost)
    }

    @Test fun `profiles remain isolated without leaking scope strings into paths`() {
        val history = DailyHistoryStore(directory)
        val apple = ProfileData()
        val banana = ProfileData()
        history.register("server/player/apple", apple)
        history.register("server/player/banana", banana)
        val days = TrackerDays(history = history)
        days.record(apple, date, mapOf("WHEAT" to 1L)) { 2.0 }
        days.record(banana, date, mapOf("WHEAT" to 9L)) { 3.0 }
        history.flush()
        assertEquals(1L, days.view(apple, date).items["WHEAT"])
        assertEquals(9L, days.view(banana, date).items["WHEAT"])
        files().forEach { assertTrue(it.parent.fileName.toString().matches(Regex("[a-f0-9]{64}"))) }
    }

    @Test fun `failed migration preserves inline history and unreadable destination`() {
        val history = DailyHistoryStore(directory)
        val existing = ProfileData()
        history.register("apple", existing)
        TrackerDays(history = history).record(existing, date, mapOf("WHEAT" to 1L)) { 2.0 }
        history.flush()
        val file = files().single()
        Files.writeString(file, "broken gzip")
        val profile = ProfileData().apply {
            days[date.toString()] = DailyTrackerData().apply { items["WHEAT"] = 42 }
        }
        assertThrows(IllegalStateException::class.java) { DailyHistoryStore(directory).register("apple", profile) }
        assertEquals(42L, profile.days.getValue(date.toString()).items["WHEAT"])
        assertEquals("broken gzip", Files.readString(file))
    }

    @Test fun `several updates keep independent historical quotes across compression and reload`() {
        val history = DailyHistoryStore(directory)
        val profile = ProfileData()
        history.register("apple", profile)
        val days = TrackerDays(history = history)
        days.recordPrices(profile, date, mapOf("WHEAT" to 10L)) { _, source -> source.ordinal + 1.0 }
        days.recordPrices(profile, date, mapOf("WHEAT" to 5L)) { _, source -> (source.ordinal + 1.0) * 2 }
        history.flush()
        val file = files().single()
        val json = GZIPInputStream(Files.newInputStream(file)).bufferedReader().use { it.readText() }
        assertTrue(json.contains("BAZAAR_INSTANT_BUY"))
        val restored = ProfileData()
        val reopened = DailyHistoryStore(directory)
        reopened.register("apple", restored)
        val archived = TrackerDays(history = reopened)
        PriceSource.entries.forEach { source ->
            assertEquals(20.0 * (source.ordinal + 1), archived.view(restored, date, source).value { 99999.0 })
        }
    }
    @Test fun `failed writes keep dirty records even when the cache is full`() {
        val blocked = directory.resolve("blocked")
        Files.writeString(blocked, "temporarily not a directory")
        val history = DailyHistoryStore(blocked, cacheLimit = 1)
        val profile = ProfileData()
        history.register("apple", profile)
        val days = TrackerDays(history = history)
        days.record(profile, date, mapOf("WHEAT" to 1L)) { 2.0 }
        days.record(profile, date.plusMonths(1), mapOf("WHEAT" to 9L)) { 3.0 }
        assertFalse(history.flush())
        assertEquals(1L, days.view(profile, date).items["WHEAT"])
        assertEquals(9L, days.view(profile, date.plusMonths(1)).items["WHEAT"])
        Files.delete(blocked)
        Files.createDirectory(blocked)
        assertTrue(history.flush())
        val reopened = DailyHistoryStore(blocked)
        val restored = ProfileData()
        reopened.register("apple", restored)
        val recovered = TrackerDays(history = reopened)
        assertEquals(1L, recovered.view(restored, date).items["WHEAT"])
        assertEquals(9L, recovered.view(restored, date.plusMonths(1)).items["WHEAT"])
    }

}
