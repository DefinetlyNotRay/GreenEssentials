package dev.greenhouse

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import com.mojang.brigadier.builder.RequiredArgumentBuilder.argument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommandAliasesTest {
    private fun dispatcher(): CommandDispatcher<Any> {
        val dispatcher = CommandDispatcher<Any>()
        val root = dispatcher.register(literal<Any>("greenhouse").executes { 1 }
            .then(literal<Any>("settings").executes { 2 })
            .then(literal<Any>("layouts").executes { 3 })
            .then(literal<Any>("hud").executes { 4 }
                .then(argument<Any,Int>("x",IntegerArgumentType.integer(0))
                    .then(argument<Any,Int>("y",IntegerArgumentType.integer(0)).executes { IntegerArgumentType.getInteger(it,"x")+IntegerArgumentType.getInteger(it,"y") })))
            .then(literal<Any>("reset").then(literal<Any>("session").then(literal<Any>("confirm").executes { 5 }))))
        registerGreenEssentialsAliases(dispatcher,root)
        return dispatcher
    }
    @Test fun `all roots execute and share nested commands`() {
        val d=dispatcher()
        for(root in listOf("ge","greenessentials","greenhouse")) {
            assertEquals(1,d.execute(root,Any()))
            assertEquals(2,d.execute("$root settings",Any()))
            assertEquals(3,d.execute("$root layouts",Any()))
            assertEquals(4,d.execute("$root hud",Any()))
            assertEquals(42,d.execute("$root hud 12 30",Any()))
            assertEquals(5,d.execute("$root reset session confirm",Any()))
        }
    }
    @Test fun `direct shortcuts execute bare commands and accept coordinates`() {
        val d=dispatcher()
        for(prefix in listOf("ge","greenessentials","greenhouse")) {
            assertEquals(2,d.execute(prefix+"settings",Any()))
            assertEquals(3,d.execute(prefix+"layouts",Any()))
            assertEquals(4,d.execute(prefix+"hud",Any()))
            assertEquals(42,d.execute(prefix+"hud 12 30",Any()))
        }
        assertEquals(3,d.execute("shgreenhouseblueprint",Any()))
    }
    @Test fun `alias completions expose the same subcommands`() {
        val d=dispatcher()
        val expected=d.getCompletionSuggestions(d.parse("greenhouse ",Any())).get().list.map { it.text }
        for(root in listOf("ge","greenessentials")) {
            assertEquals(expected,d.getCompletionSuggestions(d.parse("$root ",Any())).get().list.map { it.text })
        }
    }
}
