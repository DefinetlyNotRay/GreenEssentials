package dev.greenhouse

import com.google.gson.JsonObject

internal object StorageMigrations {
    /** Move the first Cost implementation's totals into the existing tracker records. */
    fun costs(settings: JsonObject) {
        settings.getAsJsonObject("profiles")?.entrySet()?.forEach { (_, value) ->
            val profile = value.asJsonObject
            val history = profile.getAsJsonObject("costs") ?: return@forEach
            history.remove("all")?.let { if (!profile.has("cost")) profile.add("cost", it) }
            history.remove("days")?.asJsonObject?.entrySet()?.forEach { (date, cost) ->
                val days = profile.getAsJsonObject("days") ?: JsonObject().also { profile.add("days", it) }
                val day = days.getAsJsonObject(date) ?: JsonObject().also { days.add(date, it) }
                if (!day.has("cost")) day.add("cost", cost)
            }
        }
    }
}
