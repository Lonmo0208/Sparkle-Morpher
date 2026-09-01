package com.micaftic.morpher.core.compat.slashblade;

import com.micaftic.morpher.geckolib3.geo.animated.AnimatedGeoModel;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Loader-neutral facade; see {@link SlashBladeCompat} for the gating scheme.
 *
 * <p>第二/第三人称渲染采用模型骨骼锚点定位（复用 YSM 1.20 原版逻辑），
 * 而不是官方 MMD 版的原版实体坐标硬编码偏移，确保任意比例的自定义模型上位置正确。</p>
 */
public final class SlashBladeRenderer {

    private SlashBladeRenderer() {
    }

    public static void renderOnEntity(LivingEntity entity, AnimatedGeoModel model, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, ItemStack stack, float partialTick) {
        if (!SlashBladeModState.LOADED) {
            return;
        }
        SlashBladeBridge.renderMainHandBladeOnBones(entity, stack, partialTick, poseStack, bufferSource, packedLight, model);
    }

    public static void renderRightWaist(AnimatedGeoModel model, LivingEntity entity, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, ItemStack stack) {
        if (!SlashBladeModState.LOADED) {
            return;
        }
        SlashBladeBridge.renderWaistBladeOnBones(stack, entity, poseStack, bufferSource, packedLight, model);
    }
}
