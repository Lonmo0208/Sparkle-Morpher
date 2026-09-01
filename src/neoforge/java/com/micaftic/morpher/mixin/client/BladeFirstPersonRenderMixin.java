package com.micaftic.morpher.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import mods.flammpfeil.slashblade.client.renderer.layers.LayerMainBlade;
import mods.flammpfeil.slashblade.client.renderer.model.BladeFirstPersonRender;
import mods.flammpfeil.slashblade.capability.slashblade.BladeStateAccess;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * BladeFirstPersonRenderMixin
 *
 * Resharped's BladeFirstPersonRender.render() does identity() + manual yaw/pitch
 * reconstruction which makes the blade NOT follow camera rotation.
 * We skip the entire method and render LayerMainBlade directly using the
 * vanilla ItemInHandRenderer PoseStack (full camera rotation + swing animation).
 *
 * Full camera follow (yaw+pitch). Fixed tuned position:
 *   translate(BladePosDebug.offsetX/Y/Z) + optional Z180.
 */
@Mixin(value = BladeFirstPersonRender.class, remap = false, priority = 1500)
public class BladeFirstPersonRenderMixin {

    @Inject(
        method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void sm$renderBlade(PoseStack matrixStack, MultiBufferSource bufferIn, int combinedLightIn, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) { ci.cancel(); return; }

        LayerMainBlade<LocalPlayer, ?> layer = sm$getOrCreateLayer(player);
        if (layer == null) { ci.cancel(); return; }

        boolean flag = mc.getCameraEntity() instanceof LivingEntity
            && ((LivingEntity) mc.getCameraEntity()).isSleeping();
        if (mc.gameMode == null || !(mc.options.getCameraType() == CameraType.FIRST_PERSON && !flag && !mc.options.hideGui
            && !mc.gameMode.isAlwaysFlying())) { ci.cancel(); return; }

        ItemStack stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty()) { ci.cancel(); return; }
        if (BladeStateAccess.of(stack).isEmpty()) { ci.cancel(); return; }

        float partialTicks = mc.getTimer().getGameTimeDeltaPartialTick(false);

        matrixStack.pushPose();
        matrixStack.translate(-0.05f, 1.10f, 0.65f);
        matrixStack.mulPose(Axis.ZP.rotationDegrees(180.0f));

        layer.render(matrixStack, bufferIn, combinedLightIn, player, 0, 0, partialTicks, 0, 0, 0);

        matrixStack.popPose();
        ci.cancel();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LayerMainBlade<LocalPlayer, ?> sm$getOrCreateLayer(LocalPlayer player) {
        try {
            java.lang.reflect.Field layerField = BladeFirstPersonRender.class.getDeclaredField("layer");
            layerField.setAccessible(true);
            Object val = layerField.get(BladeFirstPersonRender.getInstance());
            if (val instanceof LayerMainBlade) {
                return (LayerMainBlade<LocalPlayer, ?>) val;
            }
        } catch (Throwable ignored) {}
        EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player);
        if (renderer instanceof RenderLayerParent) {
            return new LayerMainBlade((RenderLayerParent) renderer);
        }
        return null;
    }
}
