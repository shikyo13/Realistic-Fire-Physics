package com.zerotheabsolute.realisticfirephysics;

import com.mojang.brigadier.context.CommandContext;
import com.zerotheabsolute.realisticfirephysics.compat.LegacyNames;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Operator commands share one dispatcher tree, including the legacy spelling. */
public final class FireCommands {
    private FireCommands() {}

    public static void register(RegisterCommandsEvent event) {
        var root =
                event.getDispatcher()
                        .register(
                                Commands.literal("rfp")
                                        .requires(s -> s.hasPermission(2))
                                        .then(
                                                Commands.literal("status")
                                                        .executes(FireCommands::status))
                                        .then(
                                                Commands.literal("clear")
                                                        .executes(FireCommands::clear))
                                        .then(
                                                Commands.literal("extinguish")
                                                        .executes(FireCommands::extinguish)));
        for (String alias : new String[] {"realisticfirephysics", LegacyNames.NAMESPACE})
            event.getDispatcher()
                    .register(
                            Commands.literal(alias)
                                    .requires(s -> s.hasPermission(2))
                                    .redirect(root));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var level = source.getLevel();
        var fire = FireField.get(level);
        source.sendSuccess(() -> Component.literal(fire.status(level)), false);
        return fire.size();
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var level = source.getLevel();
        int count = FireField.get(level).clear(level);
        source.sendSuccess(
                () -> Component.literal("Cleared fire and burn marks in this dimension."), true);
        return count;
    }

    private static int extinguish(CommandContext<CommandSourceStack> context) {
        var source = context.getSource();
        var level = source.getLevel();
        int count = FireField.get(level).extinguish(level);
        source.sendSuccess(
                () -> Component.literal("Extinguished fire in this dimension. Burn marks remain."),
                true);
        return count;
    }
}
