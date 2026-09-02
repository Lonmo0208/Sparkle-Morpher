package com.micaftic.exspm_hserverysm_model.server;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Operator command tree for managing per-player upload grants.
 *
 * <p>Syntax (permission level 2 required):</p>
 * <ul>
 *   <li>{@code /exspm_upload grant <player> [hours]} — grant or extend an upload
 *       permission window (default {@code grantDurationHours}, i.e. 12h).</li>
 *   <li>{@code /exspm_upload revoke <player>} — revoke an existing grant.</li>
 *   <li>{@code /exspm_upload list} — list all currently valid grants.</li>
 * </ul>
 */
public final class YsmUploadCommand {

    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGESTIONS =
            YsmUploadCommand::suggestPlayers;

    private YsmUploadCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("exspm_upload")
                        .then(Commands.literal("grant")
                                .requires(ctx -> ctx.hasPermission(2))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .executes(ctx -> grant(ctx, YsmUploadConfig.grantDurationHours()))
                                        .then(Commands.argument("hours", IntegerArgumentType.integer(1, 24 * 365))
                                                .executes(ctx -> grant(ctx, IntegerArgumentType.getInteger(ctx, "hours"))))))
                        .then(Commands.literal("perm")
                                .requires(ctx -> ctx.hasPermission(2))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .executes(YsmUploadCommand::grantPermanent)))
                        .then(Commands.literal("revoke")
                                .requires(ctx -> ctx.hasPermission(2))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(PLAYER_SUGGESTIONS)
                                        .executes(YsmUploadCommand::revoke)))
                        .then(Commands.literal("list")
                                .requires(ctx -> ctx.hasPermission(2))
                                .executes(YsmUploadCommand::list))
                        .then(Commands.literal("help")
                                .executes(YsmUploadCommand::help)));
    }

    private static int grant(CommandContext<CommandSourceStack> ctx, int hours) {
        ServerPlayer target = resolvePlayer(ctx, "player");
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal(YsmUploadI18n.t(executor(ctx), "cmd.player.notfound")));
            return 0;
        }
        YsmUploadGrantStore.grant(target.getUUID(), hours * 3_600_000L);
        long expiry = YsmUploadGrantStore.expiryMillis(target.getUUID());
        ServerPlayer executor = executor(ctx);
        ctx.getSource().sendSuccess(
                () -> Component.literal(YsmUploadI18n.t(executor, "cmd.grant.success",
                        target.getGameProfile().getName(), hours, java.time.Instant.ofEpochMilli(expiry))),
                true);
        return Command.SINGLE_SUCCESS;
    }

    private static int grantPermanent(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer target = resolvePlayer(ctx, "player");
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal(YsmUploadI18n.t(executor(ctx), "cmd.player.notfound")));
            return 0;
        }
        YsmUploadGrantStore.grantPermanent(target.getUUID());
        ctx.getSource().sendSuccess(
                () -> Component.literal(YsmUploadI18n.t(executor(ctx), "cmd.grant.perm.success",
                        target.getGameProfile().getName())),
                true);
        return Command.SINGLE_SUCCESS;
    }

    private static int revoke(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer target = resolvePlayer(ctx, "player");
        if (target == null) {
            ctx.getSource().sendFailure(Component.literal(YsmUploadI18n.t(executor(ctx), "cmd.player.notfound")));
            return 0;
        }
        YsmUploadGrantStore.revoke(target.getUUID());
        ctx.getSource().sendSuccess(
                () -> Component.literal(YsmUploadI18n.t(executor(ctx), "cmd.revoke.success",
                        target.getGameProfile().getName())),
                true);
        return Command.SINGLE_SUCCESS;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<YsmUploadGrantStore.Entry> grants = YsmUploadGrantStore.snapshot();
        ServerPlayer executor = executor(ctx);
        if (grants.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(YsmUploadI18n.t(executor, "cmd.list.empty")), false);
            return Command.SINGLE_SUCCESS;
        }
        ctx.getSource().sendSuccess(
                () -> Component.literal(YsmUploadI18n.t(executor, "cmd.list.header", grants.size())), false);
        for (YsmUploadGrantStore.Entry entry : grants) {
            String validity = entry.permanent()
                    ? YsmUploadI18n.t(executor, "cmd.list.entry.permanent")
                    : YsmUploadI18n.t(executor, "cmd.list.entry.until", java.time.Instant.ofEpochMilli(entry.expiresAt()));
            ctx.getSource().sendSuccess(
                    () -> Component.literal(YsmUploadI18n.t(executor, "cmd.list.entry", shortUuid(entry.playerUuid()), validity)),
                    false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer executor = executor(ctx);
        ctx.getSource().sendSuccess(() -> Component.literal(YsmUploadI18n.t(executor, "cmd.help.header")), false);
        ctx.getSource().sendSuccess(
                () -> Component.literal(YsmUploadI18n.t(executor, "cmd.help.grant", YsmUploadConfig.grantDurationHours())),
                false);
        ctx.getSource().sendSuccess(() -> Component.literal(YsmUploadI18n.t(executor, "cmd.help.perm")), false);
        ctx.getSource().sendSuccess(() -> Component.literal(YsmUploadI18n.t(executor, "cmd.help.revoke")), false);
        ctx.getSource().sendSuccess(() -> Component.literal(YsmUploadI18n.t(executor, "cmd.help.list")), false);
        ctx.getSource().sendSuccess(() -> Component.literal(YsmUploadI18n.t(executor, "cmd.help.hint")), false);
        return Command.SINGLE_SUCCESS;
    }

    private static ServerPlayer executor(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getPlayer();
    }

    private static ServerPlayer resolvePlayer(CommandContext<CommandSourceStack> ctx, String name) {
        String raw = StringArgumentType.getString(ctx, name);
        return ctx.getSource().getServer().getPlayerList()
                .getPlayers().stream()
                .filter(p -> p.getGameProfile().getName().equalsIgnoreCase(raw))
                .findFirst().orElse(null);
    }

    private static String shortUuid(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }

    private static CompletableFuture<Suggestions> suggestPlayers(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        for (ServerPlayer player : ctx.getSource().getServer().getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().toLowerCase().startsWith(builder.getRemaining().toLowerCase())) {
                builder.suggest(player.getGameProfile().getName());
            }
        }
        return builder.buildFuture();
    }
}