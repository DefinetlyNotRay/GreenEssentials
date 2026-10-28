package dev.greenhouse

import com.google.gson.GsonBuilder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Compressed monthly history with a small cache; no directory scan or retention cutoff. */
internal class DailyHistoryStore(
    private val directory: Path,
    private val cacheLimit: Int = 4,
    private val onFailure: (Path, Exception) -> Unit = { _, _ -> },
) {
    private class MonthData {
        var format = 1
        var days = linkedMapOf<String, DailyTrackerData>()
    }

    private data class Key(val profile: String, val month: YearMonth)
    private class CachedMonth(val data: MonthData, var saved: String?, val readable: Boolean) {
        var dirty = false
    }

    private val gson = GsonBuilder().create()
    private val profiles = mutableMapOf<ProfileData, String>()
    private val cache = LinkedHashMap<Key, CachedMonth>(16, 0.75f, true)
    private val reportedFailures = mutableSetOf<Key>()

    internal val cachedMonthCount get() = cache.size

    init {
        require(cacheLimit > 0)
    }

    /** Inline history is cleared only after every migrated month has been saved successfully. */
    fun register(scope: String, profile: ProfileData) {
        if (profile in profiles) return
        val hash = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        profiles[profile] = hash
        try {
            profile.days.entries.groupBy { YearMonth.from(LocalDate.parse(it.key)) }.forEach { (date, entries) ->
                val key = Key(hash, date)
                val month = month(key)
                check(month.readable) { "Cannot migrate into unreadable history: ${path(key)}" }
                entries.forEach { (day, data) -> month.data.days[day] = data }
                month.dirty = true
                check(save(key, month)) { "Could not save migrated daily history" }
                trimCache()
            }
            profile.days.clear()
        } catch (error: Exception) {
            profiles.remove(profile)
            throw error
        }
    }

    fun read(profile: ProfileData, date: LocalDate): DailyTrackerData? {
        val month = month(key(profile, date))
        val day = month.data.days[date.toString()]
        trimCache()
        return day
    }

    /** Range navigation lists month names once and loads only matching archives. */
    fun between(profile: ProfileData, range: TrackerDateRange): Sequence<Pair<LocalDate, DailyTrackerData>> = sequence {
        val profileKey = requireNotNull(profiles[profile])
        val folder = directory.resolve(profileKey)
        val months = cache.keys.filter { it.profile == profileKey }.map { it.month }.toMutableSet()
        if (Files.isDirectory(folder)) {
            try {
                Files.list(folder).use { files ->
                    files.forEach { file ->
                        val name = file.fileName.toString()
                        if (name.endsWith(".json.gz")) {
                            runCatching { YearMonth.parse(name.removeSuffix(".json.gz")) }.getOrNull()?.let(months::add)
                        }
                    }
                }
            } catch (error: Exception) {
                onFailure(folder, IOException("Could not list daily history months", error))
            }
        }
        val first = YearMonth.from(range.start)
        val last = YearMonth.from(range.end)
        for (date in months.filter { it >= first && it <= last }.sorted()) {
            val month = month(Key(profileKey, date))
            for ((day, data) in month.data.days.toSortedMap()) {
                val parsed = LocalDate.parse(day)
                if (parsed in range) yield(parsed to data)
            }
            trimCache()
        }
    }

    /** Only callers that intend to mutate a record request edit access. */
    fun edit(profile: ProfileData, date: LocalDate, create: Boolean = true): DailyTrackerData? {
        val key = key(profile, date)
        val month = month(key)
        if (!month.readable) return null
        val day = if (create) month.data.days.getOrPut(date.toString()) { DailyTrackerData() }
            else month.data.days[date.toString()]
        if (day != null) month.dirty = true
        // Never evict the record before the caller has applied its change.
        trimCache(protected = key)
        return day
    }

    fun reset(profile: ProfileData, date: LocalDate) {
        val month = month(key(profile, date))
        if (month.readable && month.data.days.remove(date.toString()) != null) month.dirty = true
        trimCache()
    }

    fun flush(): Boolean {
        var success = true
        cache.forEach { (key, month) -> if (!save(key, month)) success = false }
        return success
    }

    private fun key(profile: ProfileData, date: LocalDate) =
        Key(requireNotNull(profiles[profile]) { "Register the profile before accessing daily history" }, YearMonth.from(date))

    private fun path(key: Key): Path = directory.resolve(key.profile).resolve("${key.month}.json.gz")

    private fun month(key: Key): CachedMonth = cache.getOrPut(key) {
        val file = path(key)
        if (!Files.exists(file)) return@getOrPut CachedMonth(MonthData(), null, true)
        try {
            val text = GZIPInputStream(Files.newInputStream(file)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val data = requireNotNull(gson.fromJson(text, MonthData::class.java))
            require(data.format == 1)
            requireNotNull(data.days).forEach { (date, day) ->
                val parsed = LocalDate.parse(date)
                require(parsed.toString() == date && YearMonth.from(parsed) == key.month)
                requireNotNull(day).validate()
            }
            CachedMonth(data, gson.toJson(data), true)
        } catch (error: Exception) {
            report(key, error)
            CachedMonth(MonthData(), null, false)
        }
    }

    private fun save(key: Key, month: CachedMonth): Boolean {
        if (!month.dirty) return true
        if (!month.readable) return false
        try {
            val text = gson.toJson(month.data)
            if (text != month.saved) {
                AtomicFile.write(path(key)) { output ->
                    GZIPOutputStream(output).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                month.saved = text
            }
            month.dirty = false
            return true
        } catch (error: Exception) {
            report(key, error)
            return false
        }
    }

    private fun trimCache(protected: Key? = null) {
        val iterator = cache.entries.iterator()
        while (cache.size > cacheLimit && iterator.hasNext()) {
            val (key, month) = iterator.next()
            // Never evict the caller's record or changes that could not be saved.
            if (key != protected && save(key, month)) iterator.remove()
        }
    }

    private fun report(key: Key, error: Exception) {
        if (reportedFailures.add(key)) onFailure(path(key), IOException("Daily history month is unavailable", error))
    }
}
