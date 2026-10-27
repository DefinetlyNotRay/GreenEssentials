package dev.greenhouse

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel

internal object Layouts {
    data class Missing(val cropId: String, val position: LorenzVec, val size: Int)

    var missing: List<Missing> = emptyList()
        private set

    private var previousMissingPlants: List<Missing> = emptyList()

    data class MissingSurface(val surface: SpawningSurfaces.Surface, val position: LorenzVec)

    var missingSurfaces: List<MissingSurface> = emptyList()
        private set

    private var previousMissingSurfaces: List<MissingSurface> = emptyList()
    val all get() = Storage.profile?.layouts.orEmpty()

    fun active(): GreenHouseStorage.MutationBlueprintStorage? =
        currentLayoutPlot()?.let { plot ->
            Storage.profile?.active?.get(plot.id)?.let(all::get)
        }

    fun use(name: String) {
        require(name in all) { "Unknown layout: $name" }
        val plot = requireNotNull(currentLayoutPlot()) { "Stand in a Greenhouse plot." }
        Storage.profile!!.active[plot.id] = name
        clear()
        Storage.save()
    }

    fun put(name: String, blueprint: GreenHouseStorage.MutationBlueprintStorage, overwrite: Boolean = false) {
        val profile = requireNotNull(Storage.profile) { "Select your SkyBlock profile: /greenhouse profile <name>" }
        val safeName = name.filterNot { it.isISOControl() || it == '§' }.trim().take(64)
        require(safeName.isNotEmpty()) { "Enter a layout name." }
        require(overwrite || safeName !in profile.layouts) { "That layout name already exists." }
        // Validate imports and captures with the same portable-format validator.
        val checked = GreenhouseBlueprintFile.decode(GreenhouseBlueprintFile.encode(safeName, blueprint)).blueprint
        profile.layouts[safeName] = checked
        Storage.save()
        if (currentLayoutPlot() != null) use(safeName)
        say("Saved layout: $safeName")
    }

    fun capture(name: String, overwrite: Boolean = false) {
        val plot = requireNotNull(currentLayoutPlot()) { "Stand in a Greenhouse plot." }
        require(GreenhouseGridScanner.isLoaded(plot)) { "Wait for the entire planting grid to load." }
        val scan = GreenhouseGridScanner.scan(plot)
        require(scan.mutations.isNotEmpty() || scan.crops.isNotEmpty()) { "No crops or mutations found in the planting grid." }
        val blueprint = GreenHouseStorage.MutationBlueprintStorage(
            mutations = scan.mutations.mapTo(mutableListOf()) { detected ->
                GreenHouseStorage.MutationPlacementStorage(
                    detected.mutation.internalId, detected.position - plot.middle, "", detected.mutation.size,
                )
            },
            importedCells = GreenhouseLayoutCapture.captureCells(plot, scan.mutations).toMutableList(),
        )
        put(name, blueprint, overwrite)
    }

    fun importClipboard(name: String) {
        val input = Minecraft.getInstance().keyboardHandler.clipboard.trim()
        require(input.toByteArray().size <= GreenhouseBlueprintFile.MAX_FILE_BYTES) { "Clipboard layout is too large." }
        if (input.startsWith("{")) {
            put(name, GreenhouseBlueprintFile.decode(input).blueprint)
            return
        }
        val codecs: List<(String) -> SkyShardsLayoutCodec.Layout> =
            when {
                input.contains("skymutations", true) -> listOf(SkyMutationsLayoutCodec::decode)
                input.contains("skylayouts", true) -> listOf(SkyLayoutsLayoutCodec::decode)
                else -> listOf(SkyShardsLayoutCodec::decode, SkyMutationsLayoutCodec::decode, SkyLayoutsLayoutCodec::decode)
            }
        val layout = codecs.firstNotNullOfOrNull { runCatching { it(input) }.getOrNull() }
        requireNotNull(layout) { "Could not decode this layout. Copy a supported share link, code, or layout JSON." }
        val blueprint = GreenHouseStorage.MutationBlueprintStorage()
        layout.placements.forEach { cell ->
            val mutation = GreenhouseMutation.fromSkyShardsId(cell.cropId)
            blueprint.importedCells.add(GreenHouseStorage.BlueprintCellStorage(cell.cropId, cell.row, cell.column, cell.target))
            if (mutation != null && !cell.target) {
                val position = LorenzVec(
                    (cell.column - 5 + mutation.size / 2).toDouble(),
                    64.0,
                    (cell.row - 5 + mutation.size / 2).toDouble(),
                )
                blueprint.mutations.add(GreenHouseStorage.MutationPlacementStorage(mutation.internalId, position, "", mutation.size))
            }
        }
        put(name, blueprint)
    }

    fun importFile(filename: String) {
        val path = Storage.directory.resolve("layouts").resolve(filename).normalize()
        require(path.startsWith(Storage.directory.resolve("layouts"))) { "Use a file inside config/greenhouse/layouts." }
        importPath(path)
    }

    fun importPath(path: Path) {
        require(Files.size(path) <= GreenhouseBlueprintFile.MAX_FILE_BYTES) { "Layout file is too large." }
        val imported = GreenhouseBlueprintFile.decode(Files.readString(path))
        put(nextName(imported.name.ifEmpty { "Imported Layout" }), imported.blueprint)
    }

    fun nextName(base: String = "Layout"): String {
        if (base !in all) return base
        var number = 2
        while ("$base $number" in all) number++
        return "$base $number"
    }

    fun export(name: String) {
        val blueprint = requireNotNull(all[name]) { "Unknown layout: $name" }
        val directory = Storage.directory.resolve("layouts")
        Files.createDirectories(directory)
        val path = directory.resolve(GreenhouseBlueprintFile.suggestedFileName(name))
        Files.writeString(path, GreenhouseBlueprintFile.encode(name, blueprint))
        say("Exported: $path")
    }

    fun delete(name: String) {
        val profile = requireNotNull(Storage.profile) { "Select a profile first." }
        require(profile.layouts.remove(name) != null) { "Unknown layout: $name" }
        profile.active.entries.removeIf { it.value == name }
        clear()
        Storage.save()
    }

    fun rename(old: String, name: String) {
        val profile = requireNotNull(Storage.profile) { "Select a profile first." }
        require(name.isNotBlank() && name.length <= 64 && name !in all) { "Choose a new, unused name (1–64 characters)." }
        val layout = requireNotNull(profile.layouts.remove(old)) { "Unknown layout: $old" }
        profile.layouts[name] = layout
        profile.active.replaceAll { _, value -> if (value == old) name else value }
        Storage.save()
    }

    fun rotate(name: String) {
        rotateClockwise(requireNotNull(all[name]) { "Unknown layout: $name" })
        clear()
        Storage.save()
    }

    fun rotateClockwise(blueprint: GreenHouseStorage.MutationBlueprintStorage) {
        val minX = blueprint.minXOffset
        val maxX = blueprint.maxXOffset
        blueprint.minXOffset = -blueprint.maxZOffset - 1
        blueprint.maxXOffset = -blueprint.minZOffset - 1
        blueprint.minZOffset = minX
        blueprint.maxZOffset = maxX
        blueprint.mutations.forEach { placement ->
            val size = GreenhouseMutation.fromInternalId(placement.mutationId)?.size ?: placement.size
            val offset = placement.offset
            placement.offset = LorenzVec(-offset.z - size % 2, offset.y, offset.x)
        }
        blueprint.importedCells.forEach { cell ->
            val originalRow = cell.row
            val size = GreenhouseMutation.fromSkyShardsId(cell.cropId)?.size ?: 1
            cell.row = cell.column
            cell.column = 10 - size - originalRow
        }
    }

    fun target(blueprint: GreenHouseStorage.MutationBlueprintStorage): GreenhouseMutation? {
        if (blueprint.targetMutationId == "NONE") return null
        if (blueprint.targetMutationId.isNotEmpty()) return GreenhouseMutation.fromInternalId(blueprint.targetMutationId)

        val entries = if (blueprint.importedCells.isNotEmpty()) {
            blueprint.importedCells.map { cell ->
                val size = GreenhouseMutation.fromSkyShardsId(cell.cropId)?.size ?: 1
                GreenhouseLayoutAnalysis.Entry(cell.cropId, size * size, cell.target)
            }
        } else {
            blueprint.mutations.map { placement ->
                val size = GreenhouseMutation.fromInternalId(placement.mutationId)?.size ?: placement.size
                GreenhouseLayoutAnalysis.Entry(placement.mutationId.lowercase(), size * size)
            }
        }
        return GreenhouseLayoutAnalysis.inferTarget(entries)
    }

    fun clear() {
        missing = emptyList()
        previousMissingPlants = emptyList()
        missingSurfaces = emptyList()
        previousMissingSurfaces = emptyList()
    }

    fun refresh() {
        val plot = currentLayoutPlot() ?: run {
            clear()
            return
        }
        val blueprint = active() ?: run {
            clear()
            return
        }
        if (!Storage.settings.overlay || !GreenhouseGridScanner.isLoaded(plot)) {
            clear()
            return
        }
        val level = Minecraft.getInstance().level ?: run {
            clear()
            return
        }
        val surfaces = findMissingSurfaces(blueprint, plot, level)
        missingSurfaces = if (surfaces == previousMissingSurfaces) surfaces else emptyList()
        previousMissingSurfaces = surfaces
        val plants = findMissingPlants(blueprint, plot)
        // Require two consecutive scans before showing missing placements.
        missing = if (plants == previousMissingPlants) plants else emptyList()
        previousMissingPlants = plants
    }

    private fun findMissingSurfaces(
        blueprint: GreenHouseStorage.MutationBlueprintStorage,
        plot: GardenPlot,
        level: ClientLevel,
    ): List<MissingSurface> = SpawningSurfaces.expected(blueprint, plot.middle).mapNotNull { cell ->
        val position = LorenzVec(plot.middle.toBlockPos().x + cell.column - 5.0, 73.0, plot.middle.toBlockPos().z + cell.row - 5.0)
        val actual = SurfaceBlocks.surface(level.getBlockState(position.toBlockPos()))
        if (SpawningSurfaces.accepts(cell, actual)) null else MissingSurface(cell.surface, position)
    }

    private fun findMissingPlants(blueprint: GreenHouseStorage.MutationBlueprintStorage, plot: GardenPlot): List<Missing> {
        val area = GreenhouseGridScanner.area(plot)
        val liveMutations = GreenhouseMutationScanner.scan(area).toMutableList()
        val targetMutation = target(blueprint)
        val neededDevourerInputs = if (Storage.settings.devourerInputsOnly && targetMutation == GreenhouseMutation.DEVOURER) {
            val occupied = GreenhouseGridScanner.occupiedMutationCells(plot.middle, liveMutations)
                .mapTo(mutableSetOf()) { (row, column) -> DevourerLayout.Cell(row, column) }
            DevourerRoots.positions.mapTo(occupied) { DevourerLayout.cellAt(it, plot.middle) }
            DevourerLayout.neededInputs(blueprint, occupied)
        } else {
            null
        }
        return buildList {
            blueprint.mutations.forEach { saved ->
                val mutation = GreenhouseMutation.fromInternalId(saved.mutationId) ?: return@forEach
                if (mutation == targetMutation) return@forEach
                val savedPosition = plot.middle + saved.offset
                val expected = if (blueprint.importedCells.isNotEmpty()) savedPosition.copy(y = 74.0) else savedPosition
                if (neededDevourerInputs != null && DevourerLayout.cellAt(expected, plot.middle) !in neededDevourerInputs) return@forEach
                val match = liveMutations.filter {
                    it.mutation == mutation && matchesAnchor(it.position, expected, mutation.size)
                }.minByOrNull { it.position.distanceSq(expected) }
                if (match != null) {
                    liveMutations.remove(match)
                } else {
                    val corner = LorenzVec(expected.x - mutation.size / 2, 74.0, expected.z - mutation.size / 2)
                    add(Missing(mutation.internalId.lowercase(), corner, mutation.size))
                }
            }
            for (cell in blueprint.importedCells) {
                if (cell.target || GreenhouseMutation.fromSkyShardsId(cell.cropId) != null) continue
                if (neededDevourerInputs != null) continue
                val category = CropCategory.fromCropId(cell.cropId) ?: continue
                val position = LorenzVec(plot.middle.x + cell.column - 5, 74.0, plot.middle.z + cell.row - 5)
                if (GreenhouseCropScanner.isMissingCrop(position, category)) add(Missing(cell.cropId, position, 1))
            }
        }
    }

    fun matchesAnchor(actual: LorenzVec, expected: LorenzVec, size: Int): Boolean {
        val tolerance = if (size <= 1) 0.1 else size / 2.0 + 0.25
        val deltaX = actual.x - expected.x
        val deltaZ = actual.z - expected.z
        val horizontal = if (size <= 1) {
            deltaX * deltaX + deltaZ * deltaZ <= tolerance * tolerance
        } else {
            abs(deltaX) <= tolerance && abs(deltaZ) <= tolerance
        }
        return horizontal && abs(actual.y - expected.y) <= 3.0
    }
}
