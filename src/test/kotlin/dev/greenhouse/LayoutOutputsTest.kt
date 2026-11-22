package dev.greenhouse

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LayoutOutputsTest {
    @Test fun `multiple explicit outputs survive inference`() {
        assertEquals(setOf(GreenhouseMutation.CREAMBLOOM, GreenhouseMutation.CHOCOBERRY), GreenhouseLayoutAnalysis.inferOutputs(listOf(
            GreenhouseLayoutAnalysis.Entry("creambloom", explicitTarget = true),
            GreenhouseLayoutAnalysis.Entry("chocoberry", explicitTarget = true),
            GreenhouseLayoutAnalysis.Entry("choconut", 8),
        )))
    }

    @Test fun `automatic inference keeps all satisfied recipes`() {
        val entries = listOf(GreenhouseLayoutAnalysis.Entry("choconut", 8), GreenhouseLayoutAnalysis.Entry("gloomgourd", 2))
        assertEquals(setOf(GreenhouseMutation.CREAMBLOOM, GreenhouseMutation.CHOCOBERRY), GreenhouseLayoutAnalysis.inferOutputs(entries))
    }

}
