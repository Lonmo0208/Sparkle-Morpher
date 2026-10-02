package com.micaftic.morpher.core.compat.slashblade;

import com.micaftic.morpher.geckolib3.geo.animated.AnimatedGeoModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Loader-neutral facade; see {@link SlashBladeCompat} for the gating scheme.
 * The {@code model} parameter supplies the model-relative blade and hand
 * locators so the renderer follows the transformed model rather than the
 * vanilla player-sized entity space.
 */
public final class SlashBladeRenderer {

    private SlashBladeRenderer() {
    }

    public static void renderOnEntity(LivingEntity entity, AnimatedGeoModel model, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, ItemStack stack, float partialTick) {
        if (!SlashBladeModState.LOADED) {
            return;
        }
        // 官方 YSM 1.20 骨骼定位算法：leftWaistBlade/bladeBones/sheathBones 三锚点 +
        // scale==0 隐藏检查，任意比例自定义模型上位置正确。不能使用上游 1.2.7 的
        // renderAttachedToModel（bladeBones 兜底 leftHandBones，无腰挂锚点，刀会贴到手掌）。
        SlashBladeBridge.renderMainHandBladeOnBones(entity, stack, partialTick, poseStack, bufferSource, packedLight, model);
    }

    public static void renderRightWaist(AnimatedGeoModel model, LivingEntity entity, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, ItemStack stack) {
        if (!SlashBladeModState.LOADED) {
            return;
        }
        SlashBladeBridge.renderWaistBladeOnBones(stack, entity, poseStack, bufferSource, packedLight, model);
    }
}