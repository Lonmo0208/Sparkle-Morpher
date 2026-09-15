package com.micaftic.morpher.client.event;

import com.micaftic.morpher.YesSteveModel;
import com.micaftic.morpher.capability.PlayerCapability;
import com.micaftic.morpher.capability.client.PlayerCapabilityClientStore;
import com.micaftic.morpher.client.renderer.ModelPreviewRenderer;
import com.micaftic.morpher.client.render.PlayerRenderPolicy;
import com.micaftic.morpher.client.renderer.RendererManager;
import com.micaftic.morpher.core.config.ConfigPolicies;
import com.micaftic.morpher.util.CameraUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.player.Player;
import com.micaftic.morpher.core.compat.firstperson.FirstPersonCompat;
import com.micaftic.morpher.core.compat.playeranimator.PlayerAnimatorCompat;
import com.micaftic.morpher.core.compat.realcamera.RealCameraCompat;

public class ReplacePlayerRenderEvent {

    private ReplacePlayerRenderEvent() {
    }

    public static boolean onRenderPlayerPre(Player entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (!YesSteveModel.isAvailable()) {
            return false;
        }
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        PlayerCapability cap;
        if (PlayerCapabilityClientStore.isFakePlayerEntity(entity)) {
            // 虚拟玩家实体（超大实体ID，如 voxy 服务端生成的远程替身）会被
            // PlayerCapability.get() 的 fake-id 拦截而拿不到能力，落回原版渲染。
            // 按其真实 UUID 取能力，交由 PlayerRenderPolicy 统一决策。
            cap = PlayerCapabilityClientStore.getByUuid(entity.getUUID()).orElse(null);
        } else {
            cap = PlayerCapability.get(entity).orElse(null);
        }
        boolean firstPersonSuppressionSatisfied = cap != null
                && (!CameraUtil.isFirstPerson(cap)
                    || FirstPersonCompat.isFirstPersonActive()
                    || RealCameraCompat.isActive()
                    || ConfigPolicies.render().disableExternalFirstPersonAnimation()
                    || !PlayerAnimatorCompat.isPlayerAnimated(localPlayer));
        PlayerRenderPolicy.Decision decision = PlayerRenderPolicy.decide(new PlayerRenderPolicy.GateInputs(
                true,
                entity.equals(localPlayer),
                ConfigPolicies.render().disableSelfModel(),
                ConfigPolicies.render().disableOtherModel(),
                entity.isSpectator(),
                cap != null && cap.isModelActive(),
                firstPersonSuppressionSatisfied
        ));
        if (decision == PlayerRenderPolicy.Decision.RENDER_CUSTOM) {
            float previewYaw = ModelPreviewRenderer.isInventoryPreviewFrontFacing() ? ModelPreviewRenderer.FRONT_FACING_YAW : entityYaw;
            RendererManager.getPlayerRenderer().render(cap, previewYaw, partialTick, poseStack, bufferSource, packedLight);
            return true;
        }
        return false;
    }
}
