package dev.greenhouse

import com.google.gson.JsonParser

/** Reference snapshot of crop identities, independent of recipes and growth-stage guesses. */
internal object MutationDrops {
    private val crops = setOf("WHEAT","SEEDS","CARROT_ITEM","POTATO_ITEM","NETHER_STALK","PUMPKIN","MELON",
        "INK_SACK:3","SUGAR_CANE","CACTUS","RED_MUSHROOM","BROWN_MUSHROOM","DOUBLE_PLANT","MOONFLOWER","WILD_ROSE")
    fun decode(text: String): Map<String,Set<String>> {
        val root=JsonParser.parseString(text).asJsonObject
        require(root["format"].asInt==1)
        return root.getAsJsonObject("drops").entrySet().associate { (id,value) ->
            require(GreenhouseMutation.fromInternalId(id)!=null) { "Unknown mutation: $id" }
            val ids=value.asJsonArray.map { it.asString }.toSet()
            require(ids.all { it in crops }) { "Invalid crop drops: $id" }
            id to ids
        }
    }
    private val profiles by lazy {
        MutationDrops::class.java.getResourceAsStream("/mutation-drops.json")!!.bufferedReader().use { decode(it.readText()) }
    }
    fun crops(id: String): Set<String> = profiles[id].orEmpty()
}

/** A placement is confirmed only when a new matching model appears at the selected cell. */
internal class PlacementHistory {
    private data class Attempt(val id: String,val before: Set<Int>,val expires: Long)
    private val attempts=mutableMapOf<String,Attempt>()
    fun attempt(cell: String,id: String,before: Set<Int>,now: Long) { attempts[cell]=Attempt(id,before,now+3000) }
    fun confirm(models: Map<String,Map<String,Set<Int>>>,placed: MutableMap<String,String>,now: Long) {
        attempts.entries.removeIf { (cell,attempt) ->
            when {
                now>attempt.expires -> true
                models[cell]?.get(attempt.id)?.any { it !in attempt.before }==true -> { placed[cell]=attempt.id; true }
                else -> false
            }
        }
    }
    fun clear()=attempts.clear()
    fun pending()=attempts.isNotEmpty()
}
