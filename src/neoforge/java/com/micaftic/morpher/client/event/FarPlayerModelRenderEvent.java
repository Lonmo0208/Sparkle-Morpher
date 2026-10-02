package com.micaftic.morpher.client.event;

import com.micaftic.morpher.YesSteveModel;
import com.micaftic.morpher.capability.PlayerCapability;
import com.micaftic.morpher.capability.client.PlayerCapabilityClientStore;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.opengl.GL11;

/**
 * 原版实体通道没画到的真玩家模型补渲染。
 *
 * <p>真人实体并不总是在原版实体循环里被提交渲染：其他玩家的剔除线是
 * {@code RemotePlayer.shouldRenderAtSqrDistance}（包围盒 ×10 ×64 × 本机视距系数，实测远大于 64 格），
 * 之外还有视锥剔除，以及 {@code LevelRenderer} 里的 {@code isSectionCompiled} 判定——区块没被
 * 原版编译到（超出原版视距、只剩 Voxy/VSS 的 LOD 地形）时，该位置的实体整帧都不会被提交。
 * 这些位置上的真玩家模型于是整片消失，只剩 VSS/voxy 替身或调试碰撞箱。</p>
 *
 * <p>补渲染不去复刻原版那套判定，只按「这一帧原版有没有处理过这个玩家」来补：原版实体通道每次
 * 进入渲染都会在 {@link ReplacePlayerRenderEvent} 里登记实体 ID，这里只处理没登记到的玩家，
 * 用与原版 {@code LevelRenderer.renderEntity} 相同的参数（真实体、插值位置与朝向、原版光照、
 * 视锥判定）补一次模型。这样无论原版因为距离、视锥还是区块编译而没画，模型都会被补上，
 * 且不会与原版通道重复绘制。</p>
 */
public final class FarPlayerModelRenderEvent {

    private FarPlayerModelRenderEvent() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(FarPlayerModelRenderEvent::onRenderLevel);
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            // 当帧登记必须等这一帧所有渲染通道都跑完再清：原版实体通道与各远景模组的替身通道
            // （VSS / voxy）都在同一帧内先后调用 morpher 的钩子，若在补渲染通道里就清空，
            // 排在它后面的那条替身通道会以为没人画过而重复绘制，表现为模型重叠闪烁。
            ReplacePlayerRenderEvent.endFrame();
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        renderMissedPlayers(event);
    }

    private static void renderMissedPlayers(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer localPlayer = minecraft.player;
        if (level == null || localPlayer == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        Frustum frustum = event.getFrustum();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();

        boolean depthTestEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        boolean renderedAny = false;
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.depthFunc(515);
        try {
            for (AbstractClientPlayer player : level.players()) {
                if (player == localPlayer || player.isRemoved() || PlayerCapabilityClientStore.isFakePlayerEntity(player)) {
                    continue;
                }
                if (ReplacePlayerRenderEvent.wasHandledThisFrame(player)) {
                    continue;
                }
                if (!ReplacePlayerRenderEvent.isWithinFarModelDistance(player) || !frustum.isVisible(player.getBoundingBox())) {
                    continue;
                }
                PlayerCapability capability = PlayerCapability.get(player).orElse(null);
                if (!ReplacePlayerRenderEvent.willRenderCustom(player, capability, localPlayer)) {
                    continue;
                }
                double drawnX = Mth.lerp(partialTick, player.xOld, player.getX());
                double drawnY = Mth.lerp(partialTick, player.yOld, player.getY());
                double drawnZ = Mth.lerp(partialTick, player.zOld, player.getZ());
                int light = dispatcher.getPackedLightCoords(player, partialTick);
                double playerDistSqr = player.distanceToSqr(camera.x, camera.y, camera.z);
                // 超出原版地形覆盖范围时，主 framebuffer 的深度对那里的 LOD 地形本就无效；
                // 关掉深度比较可消除远处模型自身面片的 z-fighting（闪烁），深度写入保留。
                boolean noDepth = ReplacePlayerRenderEvent.shouldIgnoreDepthForFarDraw(playerDistSqr);
                if (noDepth) {
                    bufferSource.endBatch();
                    RenderSystem.disableDepthTest();
                }
                try {
                    dispatcher.render(
                            player,
                            drawnX - camera.x,
                            drawnY - camera.y,
                            drawnZ - camera.z,
                            Mth.lerp(partialTick, player.yRotO, player.getYRot()),
                            partialTick,
                            event.getPoseStack(),
                            bufferSource,
                            light
                    );
                    if (noDepth) {
                        // 必须在状态窗口内 flush，几何只有光栅化时才用到当前深度状态。
                        bufferSource.endBatch();
                    }
                } finally {
                    if (noDepth) {
                        RenderSystem.enableDepthTest();
                    }
                }
                renderedAny = true;
                ReplacePlayerRenderEvent.claimRender(player.getUUID());
            }
        } finally {
            if (renderedAny) {
                bufferSource.endBatch();
            }
            RenderSystem.depthFunc(depthFunc);
            RenderSystem.depthMask(depthMask);
            if (!depthTestEnabled) {
                RenderSystem.disableDepthTest();
            }
        }
    }

}
