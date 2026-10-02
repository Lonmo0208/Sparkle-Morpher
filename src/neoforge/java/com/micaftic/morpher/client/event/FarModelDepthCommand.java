package com.micaftic.morpher.client.event;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 远处玩家模型两种画法的临时切换命令（客户端本地执行，联机服务器也能用）：
 *
 * <ul>
 *   <li>{@code /ysmfardepth c} —— 远处不做深度比较：不闪烁，但模型自我遮挡按绘制顺序；</li>
 *   <li>{@code /ysmfardepth a} —— 远处照旧做深度比较：自我遮挡正常，但远处会闪；</li>
 *   <li>{@code /ysmfardepth status} —— 看当前用的是哪种（有没有被命令覆盖）。</li>
 * </ul>
 *
 * <p>命令只改本次游戏的运行时取值，重启回到配置文件 {@code FarModelNoDepthTest} 的值。
 * 想永久生效就改那个配置项。</p>
 */
public final class FarModelDepthCommand {

    private FarModelDepthCommand() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(FarModelDepthCommand::onRegisterClientCommands);
    }

    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ysmfardepth");
        root.then(Commands.literal("c").executes(ctx -> apply(ctx.getSource(), true)));
        root.then(Commands.literal("a").executes(ctx -> apply(ctx.getSource(), false)));
        root.then(Commands.literal("status").executes(ctx -> {
            ctx.getSource().sendSystemMessage(Component.literal("[SM] " + describe()));
            return 1;
        }));
        event.getDispatcher().register(root);
    }

    private static int apply(CommandSourceStack source, boolean noDepth) {
        ReplacePlayerRenderEvent.setFarModelNoDepthOverride(noDepth);
        source.sendSystemMessage(Component.literal("[SM] " + describe()));
        return 1;
    }

    private static String describe() {
        Boolean override = ReplacePlayerRenderEvent.getFarModelNoDepthOverride();
        String mode = ReplacePlayerRenderEvent.isFarModelNoDepthTest()
                ? "C：远处不做深度比较（不闪，自我遮挡按绘制顺序）"
                : "A：远处照旧做深度比较（自我遮挡正常，远处会闪）";
        return mode + (override == null ? "（取配置文件的值，重启后回到这个值）" : "（本次游戏内手动切换，重启回配置文件的值）");
    }
}
