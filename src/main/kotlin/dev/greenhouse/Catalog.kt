package dev.greenhouse

internal object Catalog {
    data class Unit(val base: String, val amount: Long)

    val units: Map<String, Unit> = buildMap {
        fun family(base: String, vararg forms: Pair<String, Long>) {
            put(base, Unit(base, 1))
            forms.forEach { (id, amount) -> put(id, Unit(base, amount)) }
        }
        family("WHEAT", "HAY_BLOCK" to 9, "ENCHANTED_WHEAT" to 160, "ENCHANTED_HAY_BALE" to 25600)
        family("SEEDS", "ENCHANTED_SEEDS" to 160, "BOX_OF_SEEDS" to 25600)
        family("CARROT_ITEM", "ENCHANTED_CARROT" to 160, "ENCHANTED_GOLDEN_CARROT" to 25600)
        family("POTATO_ITEM", "ENCHANTED_POTATO" to 160, "ENCHANTED_BAKED_POTATO" to 25600)
        family("NETHER_STALK", "ENCHANTED_NETHER_STALK" to 160, "MUTANT_NETHER_STALK" to 25600)
        family("PUMPKIN", "ENCHANTED_PUMPKIN" to 160, "POLISHED_PUMPKIN" to 25600)
        family("MELON", "MELON_BLOCK" to 9, "ENCHANTED_MELON" to 160, "ENCHANTED_MELON_BLOCK" to 25600)
        family("INK_SACK:3", "ENCHANTED_COCOA" to 160, "ENCHANTED_COOKIE" to 25600)
        family("SUGAR_CANE", "ENCHANTED_SUGAR" to 160, "ENCHANTED_SUGAR_CANE" to 25600)
        family("CACTUS", "CACTUS_GREEN" to 1, "ENCHANTED_CACTUS_GREEN" to 160, "ENCHANTED_CACTUS" to 25600)
        family("RED_MUSHROOM", "HUGE_MUSHROOM_2" to 9, "ENCHANTED_RED_MUSHROOM" to 160, "ENCHANTED_HUGE_MUSHROOM_2" to 25600)
        family("BROWN_MUSHROOM", "HUGE_MUSHROOM_1" to 9, "ENCHANTED_BROWN_MUSHROOM" to 160, "ENCHANTED_HUGE_MUSHROOM_1" to 25600)
        family("DOUBLE_PLANT", "ENCHANTED_SUNFLOWER" to 160, "COMPACTED_SUNFLOWER" to 25600)
        family("MOONFLOWER", "ENCHANTED_MOONFLOWER" to 160, "COMPACTED_MOONFLOWER" to 25600)
        family("WILD_ROSE", "ENCHANTED_WILD_ROSE" to 160, "COMPACTED_WILD_ROSE" to 25600)
        listOf(
                "CROPIE",
                "SQUASH",
                "FERMENTO",
                "HELIANTHUS",
                "SEASONING",
                "BURROWING_SPORES",
                "RAREFINDER_CHIP",
                "CORNUCOPIA",
                "CARROT_ZEST",
                "DEEPFRIES",
                "AGGOURDIAN",
                "CANE_KNOT",
                "MELON_JUICE",
                "CACTUS_FLOWER",
                "DESIGNER_COFFEE_BEANS",
                "FEASTFUNGUS",
                "BOTROOT",
                "SALTED_SUNFLOWER_SEEDS",
                "CRYSTALIZED_MOONLIGHT",
                "FLORAL_GELATIN",
                "WARTY",
                "ETHEREAL_VINE",
                "ALL_IN_ALOE_FRAGMENT",
            )
            .forEach { family(it) }
        GreenhouseMutation.entries.filter { it.internalId !in setOf("TIMESTALK", "ZOMBUD") }.forEach { family(it.internalId) }
    }
    val aliases = buildMap {
        units.keys.forEach { put(it.replace('_', ' ').lowercase(), it) }
        put("all-in aloe fragment", "ALL_IN_ALOE_FRAGMENT")
        put("carrot", "CARROT_ITEM")
        put("potato", "POTATO_ITEM")
        put("nether wart", "NETHER_STALK")
        put("melon slice", "MELON")
        put("cocoa beans", "INK_SACK:3")
        put("sunflower", "DOUBLE_PLANT")
        put("enchanted cocoa beans", "ENCHANTED_COCOA")
        put("enchanted nether wart", "ENCHANTED_NETHER_STALK")
        GreenhouseMutation.entries.forEach { put(it.displayName.lowercase(), it.internalId) }
    }

    fun canonical(items: Map<String, Long>): Map<String, Long> = buildMap {
        items.forEach { (id, count) ->
            units[id]?.let { unit ->
                put(unit.base, getOrDefault(unit.base, 0) + count * unit.amount)
            }
        }
    }
}
