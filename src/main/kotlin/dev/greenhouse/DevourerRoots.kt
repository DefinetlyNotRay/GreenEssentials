package dev.greenhouse

import net.minecraft.world.entity.decoration.ArmorStand

internal object DevourerRoots {

    var positions: List<LorenzVec> = emptyList()
        private set

    fun isRootName(name: String): Boolean =
        clean(name).filter(Char::isLetterOrDigit).equals("devourerroots", ignoreCase = true)

    fun refresh() {
        val plot = currentLayoutPlot()
        if (plot == null || !GreenhouseGridScanner.isLoaded(plot)) {
            clear()
            return
        }
        positions = getEntitiesInBoundingBox<ArmorStand>(GreenhouseGridScanner.area(plot))
            .filter { isRootName(it.getStandHelmet().cleanName) }
            .map { it.getLorenzVec() }
            .distinctBy { DevourerLayout.cellAt(it, plot.middle) }
    }

    fun clear() {
        positions = emptyList()
    }
}
