package dev.greenhouse

import net.minecraft.client.Minecraft
import org.lwjgl.util.tinyfd.TinyFileDialogs
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Standalone equivalents of the controller methods used by the original SkyHanni layout library. */
internal object LibraryActions {
    const val MAX_LAYOUT_NAME_LENGTH = 64
    private val dialogOpen = AtomicBoolean()
    fun perform(action: () -> Unit): Boolean = try { action(); true } catch (error: Exception) {
        say(error.message ?: "Could not complete layout action."); false
    }
    fun layouts() = Layouts.all
    fun activeLayoutName(plot: Int) = Storage.profile?.active?.get(plot)
    fun targetMutation(blueprint: GreenHouseStorage.MutationBlueprintStorage) = Layouts.target(blueprint)
    fun roleCounts(blueprint: GreenHouseStorage.MutationBlueprintStorage): Map<GreenhouseLayoutAnalysis.Role,Int> {
        val entries = if (blueprint.importedCells.isNotEmpty()) blueprint.importedCells.map {
            val size = GreenhouseMutation.fromSkyShardsId(it.cropId)?.size ?: 1
            GreenhouseLayoutAnalysis.Entry(it.cropId,size*size,it.target)
        } else blueprint.mutations.map {
            val size = GreenhouseMutation.fromInternalId(it.mutationId)?.size ?: it.size
            GreenhouseLayoutAnalysis.Entry(it.mutationId.lowercase(),size*size)
        }
        return entries.groupingBy { GreenhouseLayoutAnalysis.roleFor(it,Layouts.target(blueprint)) }.eachCount()
    }
    fun loadLayout(plot: Int,name: String) {
        require(currentPlot()?.id == plot) { "Stand in the Greenhouse plot where you want to load this layout." }
        Layouts.use(name)
    }
    fun unloadLayout(plot: Int) { Storage.profile?.active?.remove(plot); Layouts.clear(); Storage.save() }
    fun captureGreenhouse(name: String? = null) = Layouts.capture(name ?: Layouts.nextName(),overwrite=name != null)
    fun importFromClipboard() = Layouts.importClipboard(Layouts.nextName("Imported Layout"))
    fun rotateLayout(name: String) = Layouts.rotate(name)
    fun deleteLayout(name: String) = Layouts.delete(name)
    fun renameLayout(old: String,name: String): Boolean = perform {
        if (old != name.trim()) Layouts.rename(old,name.trim())
    }
    fun importLayoutFile(plot: Int) {
        fileDialog(false,"Import Greenhouse Layout",null) { path ->
            // put() activates the imported layout on the current plot, if one is recognized.
            Layouts.importPath(path)
        }
    }
    fun exportLayout(name: String) {
        val blueprint = requireNotNull(Layouts.all[name]) { "Unknown layout: $name" }
        val serialized = GreenhouseBlueprintFile.encode(name,blueprint)
        fileDialog(true,"Export Greenhouse Layout",GreenhouseBlueprintFile.suggestedFileName(name)) { path ->
            val destination = Path.of(GreenhouseBlueprintFile.withFileExtension(path.toString()))
            destination.parent?.let { Files.createDirectories(it) }
            Files.writeString(destination,serialized)
            say("Exported layout to $destination")
        }
    }
    private fun fileDialog(save: Boolean,title: String,filename: String?,onSelected: (Path) -> Unit) {
        require(Storage.profile != null) { "Select your profile first: /greenhouse profile <name>" }
        if (!dialogOpen.compareAndSet(false,true)) return
        val scope = Storage.scope
        val directory = Storage.directory.resolve("layouts").toAbsolutePath()
        CompletableFuture.runAsync {
            try {
                Files.createDirectories(directory)
                check(TinyFileDialogs.tinyfd_messageBox("tinyfd_query","","ok","info",1) != 0) { "No graphical file picker is available." }
                val defaultPath = if (filename == null) directory.toString()+java.io.File.separator else directory.resolve(filename).toString()
                val selected = if (save) TinyFileDialogs.tinyfd_saveFileDialog(title,defaultPath,null,null)
                    else TinyFileDialogs.tinyfd_openFileDialog(title,defaultPath,null,null,false)
                if (selected != null) Minecraft.getInstance().execute {
                    perform {
                        require(Storage.scope == scope) { "Your profile changed while choosing a file. Please try again." }
                        onSelected(Path.of(selected))
                    }
                }
            } catch (error: Exception) {
                Minecraft.getInstance().execute { say(error.message ?: "Could not open the file picker.") }
            } finally { dialogOpen.set(false) }
        }
    }
}
