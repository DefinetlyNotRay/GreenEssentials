package dev.greenhouse

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.tree.CommandNode

internal fun <S> aliasCommand(dispatcher: CommandDispatcher<S>, name: String, target: CommandNode<S>) {
    val builder = LiteralArgumentBuilder.literal<S>(name)
    target.command?.let { builder.executes(it) }
    target.children.forEach { builder.then(it) }
    dispatcher.register(builder)
}

internal fun <S> registerGreenEssentialsAliases(dispatcher: CommandDispatcher<S>, root: CommandNode<S>) {
    for (name in listOf("ge","greenessentials")) aliasCommand(dispatcher,name,root)
    for (prefix in listOf("ge","greenessentials","greenhouse")) {
        for (child in listOf("layouts","settings","hud")) {
            aliasCommand(dispatcher,prefix+child,requireNotNull(root.getChild(child)))
        }
    }
    aliasCommand(dispatcher,"shgreenhouseblueprint",requireNotNull(root.getChild("layouts")))
}
