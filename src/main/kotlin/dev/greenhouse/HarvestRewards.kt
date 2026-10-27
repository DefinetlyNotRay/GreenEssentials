package dev.greenhouse

/** Item-specific authorization includes crop rewards from the harvested mutation itself. */
internal object HarvestRewards {
    private val bonuses =
        setOf(
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
        )

    fun items(id: String): Set<String> =
        bonuses +
            MutationDrops.crops(id) +
            setOf(id, "RED_MUSHROOM", "BROWN_MUSHROOM") +
            when (id) {
                "WHEAT" -> setOf("SEEDS")
                "ALL_IN_ALOE" -> setOf("ALL_IN_ALOE_FRAGMENT")
                else -> emptySet()
            }
}
