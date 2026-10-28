package dev.greenhouse

import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GreenhouseCommandsTest {

    private fun dispatcher(): CommandDispatcher<FabricClientCommandSource> {
        val dispatcher = CommandDispatcher<FabricClientCommandSource>()
        val root = dispatcher.register(GreenhouseCommands.createCommand())
        registerGreenEssentialsAliases(dispatcher, root)
        return dispatcher
    }

    @Test
    fun `actual command tree preserves commands and nested arguments across aliases`() {
        val dispatcher = dispatcher()
        val commands = listOf(
            "", "settings", "layouts", "help", "profile Apple", "capture My layout", "import Shared layout",
            "importfile example.shgreenhouse.json", "use My layout", "rotate My layout", "export My layout",
            "delete My layout", "unload", "target All-in Aloe", "target none", "target auto", "toggle", "overlay",
            "price WHEAT 3.5", "prices", "hud", "hud 12 30", "debug", "version", "detect", "items", "session",
            "alltime", "stats", "reset", "reset confirm", "reset session confirm", "reset alltime confirm",
            "daily", "daily 2026-10-27", "daily 2026-10-01 to 2026-10-27", "daily 2026-10-01 2026-10-27", "reset daily confirm",
            "jellyshort", "jellyshort shorten", "jellyshort hide", "jellyshort height 2",
            "testplots", "testplots on", "testplots off",
        )
        for (root in listOf("greenhouse", "greenessentials", "ge")) {
            for (command in commands) {
                val input = if (command.isEmpty()) root else "$root $command"
                val result = dispatcher.parse(input, null)
                assertFalse(result.reader.canRead(), input)
                assertNotNull(result.context.build(input).lastChild.command, input)
            }
        }
    }

    @Test
    fun `invalid arguments cannot resolve to an executable command`() {
        val dispatcher = dispatcher()
        for (command in listOf("hud -1 30", "hud 10", "price WHEAT -1", "price WHEAT", "reset unknown", "jellyshort height 9")) {
            val input = "ge $command"
            val result = dispatcher.parse(input, null)
            val executable = !result.reader.canRead() && result.context.build(input).lastChild.command != null
            assertTrue(!executable, input)
        }
    }
}
