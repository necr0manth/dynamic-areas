@file:Suppress("UnstableApiUsage")

package dev.necr0manthre.dynamicareas

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver
import io.papermc.paper.math.BlockPosition

/**
 * Reusable Brigadier API for arguments that can be either a vanilla BlockPos or a named DA vector.
 */
object PositionOrVectorArgs {

    enum class Kind {
        BLOCK_POS,
        VECTOR,
        INVERTED_VECTOR,
    }

    data class Parsed(val vec: Vec3i, val kind: Kind)

    fun blockPos(name: String): RequiredArgumentBuilder<CommandSourceStack, BlockPositionResolver> =
        Commands.argument(name, ArgumentTypes.blockPosition())

    fun vector(name: String): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument(name, StringArgumentType.string())

    fun invLiteral(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("inv")

    fun parseBlockPos(ctx: CommandContext<CommandSourceStack>, argName: String): Parsed {
        val vec = ctx.getArgument(argName, BlockPositionResolver::class.java).resolve(ctx.source).toVec3i()
        return Parsed(vec, Kind.BLOCK_POS)
    }

    fun parseVector(
        ctx: CommandContext<CommandSourceStack>,
        argName: String,
        invert: Boolean = false,
    ): Parsed? {
        val vectorName = StringArgumentType.getString(ctx, argName)
        val daPlugin = DynamicAreas.instance ?: run {
            ctx.source.sender.sendMessage("DynamicAreas plugin is not available.")
            return null
        }
        val vec = daPlugin.getSavedVector(vectorName) ?: run {
            ctx.source.sender.sendMessage("Unknown vector '$vectorName'.")
            return null
        }
        val resolved = if (invert) Vec3i(-vec.x, -vec.y, -vec.z) else vec
        return Parsed(resolved, if (invert) Kind.INVERTED_VECTOR else Kind.VECTOR)
    }
}

private fun BlockPosition.toVec3i() = Vec3i(blockX(), blockY(), blockZ())

