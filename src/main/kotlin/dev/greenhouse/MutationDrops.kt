package dev.greenhouse

import com.google.gson.JsonParser

/** Reference snapshot of crop identities, independent of recipes and growth-stage guesses. */
internal object MutationDrops {
    private val crops =
        setOf(
            "WHEAT",
            "SEEDS",
            "CARROT_ITEM",
            "POTATO_ITEM",
            "NETHER_STALK",
            "PUMPKIN",
            "MELON",
            "INK_SACK:3",
            "SUGAR_CANE",
            "CACTUS",
            "RED_MUSHROOM",
            "BROWN_MUSHROOM",
            "DOUBLE_PLANT",
            "MOONFLOWER",
            "WILD_ROSE",
        )

    fun decode(text: String): Map<String, Set<String>> {
        val root = JsonParser.parseString(text).asJsonObject
        require(root["format"].asInt == 1)
        return root.getAsJsonObject("drops").entrySet().associate { (id, value) ->
            require(GreenhouseMutation.fromInternalId(id) != null) { "Unknown mutation: $id" }
            val ids = value.asJsonArray.map { it.asString }.toSet()
            require(ids.all { it in crops }) { "Invalid crop drops: $id" }
            id to ids
        }
    }

    private val profiles by lazy {
        MutationDrops::class.java.getResourceAsStream("/mutation-drops.json")!!.bufferedReader().use { decode(it.readText()) }
    }

    fun crops(id: String): Set<String> = profiles[id].orEmpty()
}
