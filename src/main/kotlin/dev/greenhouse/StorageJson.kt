package dev.greenhouse

import com.google.gson.ExclusionStrategy
import com.google.gson.FieldAttributes
import com.google.gson.GsonBuilder

/** Daily history is saved separately; loading legacy JSON still accepts its inline days. */
internal object StorageJson {
    private val gson = GsonBuilder().setPrettyPrinting()
        .addSerializationExclusionStrategy(object : ExclusionStrategy {
            override fun shouldSkipField(field: FieldAttributes): Boolean =
                field.declaringClass == ProfileData::class.java && field.name == "days"
            override fun shouldSkipClass(type: Class<*>): Boolean = false
        }).create()

    fun encode(settings: Settings): String = gson.toJson(settings)
}
