package dev.greenhouse

internal object GreenhouseLayoutAnalysis {

    enum class Role {
        TARGET_OUTPUT,
        SPAWN_INPUT,
        YIELD_BUFF,
        UNIQUE_CROP,
        OTHER,
    }

    data class Entry(
        val cropId: String,
        val occupiedCells: Int = 1,
        val explicitTarget: Boolean = false,
    )

    fun inferTarget(entries: List<Entry>): GreenhouseMutation? {
        val explicitTargets =
            entries
                .asSequence()
                .filter(Entry::explicitTarget)
                .mapNotNull { GreenhouseMutation.fromSkyShardsId(it.cropId) }
                .distinct()
                .toList()
        if (explicitTargets.size == 1) return explicitTargets.single()
        if (explicitTargets.size > 1) return null

        val inputs = entries.filterNot(Entry::explicitTarget)
        val availableCells = inputs.groupingBy { it.cropId.lowercase() }.fold(0) { total, entry -> total + entry.occupiedCells }
        val presentMutations =
            inputs.mapNotNullTo(mutableSetOf()) {
                GreenhouseMutation.fromSkyShardsId(it.cropId)
            }
        val candidates =
            GreenhouseMutation.entries.filter { mutation ->
                mutation.spawnRequirements.isNotEmpty() &&
                    mutation.spawnRequirements.all { (cropId, amount) ->
                        availableCells.getOrDefault(cropId, 0) >= amount
                    }
            }
        if (candidates.isEmpty()) {
            return presentMutations.singleOrNull()
        }

        val scored = candidates.groupBy { candidate ->
            TargetScore(
                alreadySpawned = candidate in presentMutations,
                requiredCells = candidate.spawnRequirements.values.sum(),
                differentIngredients = candidate.spawnRequirements.size,
            )
        }
        val bestScore =
            scored.keys.maxWithOrNull(
                compareBy<TargetScore> { it.alreadySpawned }.thenBy { it.requiredCells }.thenBy { it.differentIngredients }
            ) ?: return null
        return scored.getValue(bestScore).singleOrNull()
    }

    fun inferOutputs(entries: List<Entry>): Set<GreenhouseMutation> {
        val explicit = entries.filter(Entry::explicitTarget)
            .mapNotNull { GreenhouseMutation.fromSkyShardsId(it.cropId) }.toSet()
        if (explicit.isNotEmpty()) return explicit
        val available = entries.groupingBy { it.cropId.lowercase() }.fold(0) { total, entry -> total + entry.occupiedCells }
        return GreenhouseMutation.entries.filterTo(linkedSetOf()) { mutation ->
            mutation.spawnRequirements.isNotEmpty() && mutation.spawnRequirements.all { (id, count) ->
                available.getOrDefault(id, 0) >= count
            }
        }
    }

    fun roleFor(entry: Entry, target: GreenhouseMutation?): Role {
        return roleForOutputs(entry, setOfNotNull(target))
    }

    fun roleForOutputs(entry: Entry, outputs: Set<GreenhouseMutation>): Role {
        val mutation = GreenhouseMutation.fromSkyShardsId(entry.cropId)
        if (entry.explicitTarget) return Role.TARGET_OUTPUT
        if (outputs.any { entry.cropId.lowercase() in it.spawnRequirements }) return Role.SPAWN_INPUT
        if (mutation in outputs) return Role.TARGET_OUTPUT
        if (mutation?.providesYieldBuff == true) return Role.YIELD_BUFF
        if (entry.cropId.lowercase() in uniqueCropIds) return Role.UNIQUE_CROP
        return Role.OTHER
    }

    private data class TargetScore(
        val alreadySpawned: Boolean,
        val requiredCells: Int,
        val differentIngredients: Int,
    )

    private val uniqueCropIds =
        setOf(
            "wheat",
            "potato",
            "carrot",
            "pumpkin",
            "melon",
            "cocoa_beans",
            "sugar_cane",
            "cactus",
            "nether_wart",
            "red_mushroom",
            "brown_mushroom",
            "moonflower",
            "sunflower",
            "wild_rose",
        )
}
