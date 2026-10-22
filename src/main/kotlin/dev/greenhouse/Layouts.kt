package dev.greenhouse

import net.minecraft.client.Minecraft
import java.nio.file.Files
import kotlin.math.abs

internal object Layouts {
    data class Missing(val cropId: String, val position: LorenzVec, val size: Int)
    var missing: List<Missing> = emptyList()
        private set
    private var previous: List<Missing> = emptyList()
    data class MissingSurface(val surface: SpawningSurfaces.Surface, val position: LorenzVec)
    var missingSurfaces: List<MissingSurface> = emptyList()
        private set
    private var previousSurfaces: List<MissingSurface> = emptyList()
    val all get() = Storage.profile?.layouts.orEmpty()
    fun active(): GreenHouseStorage.MutationBlueprintStorage? = currentPlot()?.let { plot ->
        Storage.profile?.active?.get(plot.id)?.let(all::get)
    }
    fun use(name: String) {
        require(name in all) { "Unknown layout: $name" }
        val plot = requireNotNull(currentPlot()) { "Stand in a Greenhouse plot." }
        Storage.profile!!.active[plot.id] = name
        clear(); Storage.save()
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
        if (currentPlot() != null) use(safeName)
        say("Saved layout: $safeName")
    }
    fun capture(name: String, overwrite: Boolean = false) {
        val plot = requireNotNull(currentPlot()) { "Stand in a Greenhouse plot." }
        require(GreenhouseGridScanner.isLoaded(plot)) { "Wait for the entire planting grid to load." }
        val scan = GreenhouseGridScanner.scan(plot)
        require(scan.mutations.isNotEmpty() || scan.crops.isNotEmpty()) { "No crops or mutations found in the planting grid." }
        put(name, GreenHouseStorage.MutationBlueprintStorage(
            mutations = scan.mutations.mapTo(mutableListOf()) {
                GreenHouseStorage.MutationPlacementStorage(it.mutation.internalId, it.position - plot.middle, "", it.mutation.size)
            },
            importedCells = GreenhouseLayoutCapture.captureCells(plot, scan.mutations).toMutableList(),
        ), overwrite)
    }
    fun importClipboard(name: String) {
        val input = Minecraft.getInstance().keyboardHandler.clipboard.trim()
        require(input.toByteArray().size <= GreenhouseBlueprintFile.MAX_FILE_BYTES) { "Clipboard layout is too large." }
        if (input.startsWith("{")) {
            put(name, GreenhouseBlueprintFile.decode(input).blueprint); return
        }
        val codecs: List<(String) -> SkyShardsLayoutCodec.Layout> = when {
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
            if (mutation != null && !cell.target) blueprint.mutations.add(GreenHouseStorage.MutationPlacementStorage(
                mutation.internalId, LorenzVec((cell.column - 5 + mutation.size / 2).toDouble(), 64.0,
                    (cell.row - 5 + mutation.size / 2).toDouble()), "", mutation.size,
            ))
        }
        put(name, blueprint)
    }
    fun importFile(filename: String) {
        val path = Storage.directory.resolve("layouts").resolve(filename).normalize()
        require(path.startsWith(Storage.directory.resolve("layouts"))) { "Use a file inside config/greenhouse/layouts." }
        importPath(path)
    }
    fun importPath(path: java.nio.file.Path) {
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
        profile.active.entries.removeIf { it.value == name }; clear(); Storage.save()
    }
    fun rename(old: String, name: String) {
        val profile = requireNotNull(Storage.profile) { "Select a profile first." }
        require(name.isNotBlank() && name.length <= 64 && name !in all) { "Choose a new, unused name (1–64 characters)." }
        val layout = requireNotNull(profile.layouts.remove(old)) { "Unknown layout: $old" }
        profile.layouts[name] = layout
        profile.active.replaceAll { _, value -> if (value == old) name else value }; Storage.save()
    }
    fun rotate(name: String) {
        rotateClockwise(requireNotNull(all[name]) { "Unknown layout: $name" }); clear(); Storage.save()
    }
    fun rotateClockwise(blueprint: GreenHouseStorage.MutationBlueprintStorage) {
        val minX = blueprint.minXOffset; val maxX = blueprint.maxXOffset
        blueprint.minXOffset = -blueprint.maxZOffset - 1; blueprint.maxXOffset = -blueprint.minZOffset - 1
        blueprint.minZOffset = minX; blueprint.maxZOffset = maxX
        blueprint.mutations.forEach {
            val size = GreenhouseMutation.fromInternalId(it.mutationId)?.size ?: it.size
            it.offset = LorenzVec(-it.offset.z - size % 2, it.offset.y, it.offset.x)
        }
        blueprint.importedCells.forEach {
            val row = it.row; val size = GreenhouseMutation.fromSkyShardsId(it.cropId)?.size ?: 1
            it.row = it.column; it.column = 10 - size - row
        }
    }
    fun target(blueprint: GreenHouseStorage.MutationBlueprintStorage): GreenhouseMutation? = when {
        blueprint.targetMutationId == "NONE" -> null
        blueprint.targetMutationId.isNotEmpty() -> GreenhouseMutation.fromInternalId(blueprint.targetMutationId)
        else -> GreenhouseLayoutAnalysis.inferTarget(if (blueprint.importedCells.isNotEmpty()) blueprint.importedCells.map {
            val size = GreenhouseMutation.fromSkyShardsId(it.cropId)?.size ?: 1
            GreenhouseLayoutAnalysis.Entry(it.cropId, size * size, it.target)
        } else blueprint.mutations.map {
            val size = GreenhouseMutation.fromInternalId(it.mutationId)?.size ?: it.size
            GreenhouseLayoutAnalysis.Entry(it.mutationId.lowercase(), size * size)
        })
    }
    fun clear() { missing = emptyList(); previous = emptyList(); missingSurfaces = emptyList(); previousSurfaces = emptyList() }
    fun refresh() {
        val plot = currentPlot() ?: run { clear(); return }
        val blueprint = active() ?: run { clear(); return }
        if (!Storage.settings.overlay || !GreenhouseGridScanner.isLoaded(plot)) { clear(); return }
        val level = Minecraft.getInstance().level ?: run { clear(); return }
        val surfaces = SpawningSurfaces.expected(blueprint,plot.middle).mapNotNull { cell ->
            val position = LorenzVec(plot.middle.toBlockPos().x + cell.column - 5.0,73.0,plot.middle.toBlockPos().z + cell.row - 5.0)
            val actual = SurfaceBlocks.surface(level.getBlockState(position.toBlockPos()))
            if (SpawningSurfaces.accepts(cell,actual)) null else MissingSurface(cell.surface,position)
        }
        missingSurfaces = if (surfaces == previousSurfaces) surfaces else emptyList()
        previousSurfaces = surfaces
        val live = GreenhouseMutationScanner.scan(GreenhouseGridScanner.area(plot)).toMutableList()
        val target = target(blueprint)
        val next = buildList {
            blueprint.mutations.forEach { saved ->
                val mutation = GreenhouseMutation.fromInternalId(saved.mutationId) ?: return@forEach
                if (mutation == target) return@forEach
                val savedPosition = plot.middle + saved.offset
                val expected = if (blueprint.importedCells.isNotEmpty()) savedPosition.copy(y=74.0) else savedPosition
                val match = live.filter { it.mutation == mutation && matchesAnchor(it.position, expected, mutation.size) }
                    .minByOrNull { it.position.distanceSq(expected) }
                if (match != null) live.remove(match) else add(Missing(mutation.internalId.lowercase(),
                    LorenzVec(expected.x - mutation.size / 2, 74.0, expected.z - mutation.size / 2), mutation.size))
            }
            blueprint.importedCells.filter { !it.target && GreenhouseMutation.fromSkyShardsId(it.cropId) == null }.forEach {
                val category = CropCategory.fromCropId(it.cropId) ?: return@forEach
                val position = LorenzVec(plot.middle.x + it.column - 5, 74.0, plot.middle.z + it.row - 5)
                if (GreenhouseCropScanner.isMissingCrop(position, category)) add(Missing(it.cropId, position, 1))
            }
        }
        // Require two consecutive scans before showing missing placements.
        missing = if (next == previous) next else emptyList()
        previous = next
    }
    fun matchesAnchor(a: LorenzVec, b: LorenzVec, size: Int): Boolean {
        val tolerance = if (size <= 1) 0.1 else size / 2.0 + 0.25
        val horizontal = if (size <= 1) (a.x-b.x)*(a.x-b.x)+(a.z-b.z)*(a.z-b.z) <= tolerance*tolerance
            else abs(a.x-b.x) <= tolerance && abs(a.z-b.z) <= tolerance
        return horizontal && abs(a.y-b.y) <= 3.0
    }
}
