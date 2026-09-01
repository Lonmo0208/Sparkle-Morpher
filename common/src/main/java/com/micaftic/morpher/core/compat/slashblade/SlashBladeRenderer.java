package com.micaftic.morpher.core.compat.slashblade;

import com.micaftic.morpher.geckolib3.core.processor.IBone;
import com.micaftic.morpher.geckolib3.geo.animated.AnimatedGeoModel;
import com.micaftic.morpher.geckolib3.util.RenderUtils;
import com.mojang.math.Axis;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class SlashBladeRenderer {

    private SlashBladeRenderer() {
    }

    private static final ResourceLocation BLADE_OBJ = ResourceLocation.fromNamespaceAndPath("slashblade", "model/blade.obj");
    private static final ResourceLocation BLADE_TEXTURE = ResourceLocation.fromNamespaceAndPath("slashblade", "model/blade.png");

    
    public static void renderBladeOnly(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, ItemStack stack) {
        if (stack.isEmpty()) return;
        Object bladeState = SlashBladeCompat.getBladeState(stack);
        if (bladeState == null) return;

        ResourceLocation texture = SlashBladeCompat.getBladeTexture(bladeState);
        if (texture == null) texture = BLADE_TEXTURE;
        ResourceLocation modelId = SlashBladeCompat.getBladeModel(bladeState);
        if (modelId == null) modelId = BLADE_OBJ;

        Object wavefront = SlashBladeCompat.loadWavefrontModel(modelId);
        if (wavefront == null) return;

        String partName = SlashBladeCompat.isBladeBroken(bladeState) ? "blade_damaged" : "blade";

        SlashBladeCompat.renderBladePart(stack, wavefront, partName, texture, poseStack, bufferSource, packedLight);
        SlashBladeCompat.renderBladePartLuminous(stack, wavefront, partName + "_luminous", texture, poseStack, bufferSource, packedLight);
        SlashBladeCompat.renderBladePart(stack, wavefront, "sheath", texture, poseStack, bufferSource, packedLight);
        SlashBladeCompat.renderBladePartLuminous(stack, wavefront, "sheath_luminous", texture, poseStack, bufferSource, packedLight);
    }

    
    public static void renderOnEntity(LivingEntity entity, AnimatedGeoModel model, PoseStack poseStack,
                                      MultiBufferSource bufferSource, int packedLight, ItemStack stack, float partialTick) {
        if (!SlashBladeCompat.isSlashBladeItem(stack)) return;

        List<IBone> leftWaistBones = model.leftWaistBones();
        List<IBone> bladeBones = model.bladeBones();
        List<IBone> sheathBones = model.sheathBones();

        if (bladeBones.isEmpty() || sheathBones.isEmpty() || leftWaistBones.isEmpty()) {
            
            renderBladeOnWaist(entity, model, poseStack, bufferSource, packedLight, stack, partialTick, leftWaistBones);
        } else {
            Object bladeState = SlashBladeCompat.getBladeState(stack);
            if (bladeState != null) {
                renderBladeWithBones(bladeState, poseStack, bufferSource, packedLight, stack,
                        leftWaistBones, bladeBones, sheathBones);
            } else {
                renderBladeOnWaist(entity, model, poseStack, bufferSource, packedLight, stack, partialTick, leftWaistBones);
            }
        }
    }

    
    public static void renderBladeWithBones(Object bladeState, PoseStack poseStack, MultiBufferSource bufferSource,
                                            int packedLight, ItemStack stack, List<IBone> leftWaistBones,
                                            List<IBone> bladeBones, List<IBone> sheathBones) {
        String partName = SlashBladeCompat.isBladeBroken(bladeState) ? "blade_damaged" : "blade";
        ResourceLocation texture = SlashBladeCompat.getBladeTexture(bladeState);
        if (texture == null) texture = BLADE_TEXTURE;
        ResourceLocation modelId = SlashBladeCompat.getBladeModel(bladeState);
        if (modelId == null) modelId = BLADE_OBJ;

        Object wavefront = SlashBladeCompat.loadWavefrontModel(modelId);
        if (wavefront == null) return;

        
        IBone lastLeftWaistBone = leftWaistBones.get(leftWaistBones.size() - 1);
        if (!isBoneHidden(lastLeftWaistBone)) {
            poseStack.pushPose();
            RenderUtils.prepMatrixForLocator(poseStack, leftWaistBones);
            poseStack.translate(0.0d, 0.025d, -0.6d);
            poseStack.scale(0.01f, 0.01f, 0.01f);
            poseStack.mulPose(Axis.YP.rotationDegrees(-90.0f));
            poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f));
            SlashBladeCompat.renderBladePart(stack, wavefront, partName, texture, poseStack, bufferSource, packedLight);
            SlashBladeCompat.renderBladePartLuminous(stack, wavefront, partName + "_luminous", texture, poseStack, bufferSource, packedLight);
            SlashBladeCompat.renderBladePart(stack, wavefront, "sheath", texture, poseStack, bufferSource, packedLight);
            SlashBladeCompat.renderBladePartLuminous(stack, wavefront, "sheath_luminous", texture, poseStack, bufferSource, packedLight);
            poseStack.popPose();
        }

        
        IBone lastBladeBone = bladeBones.get(bladeBones.size() - 1);
        if (!isBoneHidden(lastBladeBone)) {
            poseStack.pushPose();
            RenderUtils.prepMatrixForLocator(poseStack, bladeBones);
            poseStack.translate(0.0d, 0.035d, 0.0d);
            poseStack.scale(0.01f, 0.01f, 0.01f);
            poseStack.mulPose(Axis.YP.rotationDegrees(-90.0f));
            poseStack.mulPose(Axis.XP.rotationDegrees(180.0f));
            SlashBladeCompat.renderBladePart(stack, wavefront, partName, texture, poseStack, bufferSource, packedLight);
            SlashBladeCompat.renderBladePartLuminous(stack, wavefront, partName + "_luminous", texture, poseStack, bufferSource, packedLight);
            poseStack.popPose();
        }

        
        IBone lastSheathBone = sheathBones.get(sheathBones.size() - 1);
        if (!isBoneHidden(lastSheathBone)) {
            poseStack.pushPose();
            RenderUtils.prepMatrixForLocator(poseStack, sheathBones);
            poseStack.translate(0.0d, 0.025d, -0.6d);
            poseStack.scale(0.01f, 0.01f, 0.01f);
            poseStack.mulPose(Axis.YP.rotationDegrees(-90.0f));
            poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f));
            SlashBladeCompat.renderBladePart(stack, wavefront, "sheath", texture, poseStack, bufferSource, packedLight);
            SlashBladeCompat.renderBladePartLuminous(stack, wavefront, "sheath_luminous", texture, poseStack, bufferSource, packedLight);
            poseStack.popPose();
        }
    }

    
    private static void renderBladeOnWaist(LivingEntity entity, AnimatedGeoModel model, PoseStack poseStack,
                                           MultiBufferSource bufferSource, int packedLight, ItemStack stack,
                                           float partialTick, List<IBone> leftWaistBones) {
        poseStack.pushPose();
        if (!leftWaistBones.isEmpty()) {
            applyWaistBoneTransform(HumanoidArm.LEFT, poseStack, model);
        } else {
            poseStack.translate(-0.25d, 1.25d, 0.0d);
            poseStack.mulPose(Axis.XP.rotationDegrees(20.0f));
        }

        poseStack.translate(0.0d, 0.0d, -0.7d);
        poseStack.scale(0.01f, 0.01f, 0.01f);
        poseStack.mulPose(Axis.YP.rotationDegrees(-90.0f));
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f));

        if (stack.isEmpty()) {
            poseStack.popPose();
            return;
        }

        Object bladeState = SlashBladeCompat.getBladeState(stack);
        if (bladeState == null) {
            poseStack.popPose();
            return;
        }

        ResourceLocation texture = SlashBladeCompat.getBladeTexture(bladeState);
        if (texture == null) texture = BLADE_TEXTURE;
        ResourceLocation modelId = SlashBladeCompat.getBladeModel(bladeState);
        if (modelId == null) modelId = BLADE_OBJ;

        Object wavefront = SlashBladeCompat.loadWavefrontModel(modelId);
        if (wavefront == null) {
            poseStack.popPose();
            return;
        }

        String partName = SlashBladeCompat.isBladeBroken(bladeState) ? "blade_damaged" : "blade";

        
        SlashBladeCompat.renderBladePart(stack, wavefront, "sheath", texture, poseStack, bufferSource, packedLight);
        SlashBladeCompat.renderBladePartLuminous(stack, wavefront, "sheath_luminous", texture, poseStack, bufferSource, packedLight);

        
        long timeSinceLastAction = entity.level().getGameTime() - SlashBladeCompat.getBladeLastActionTime(bladeState);
        if (timeSinceLastAction < 5) {
            poseStack.translate(0.0d, 0.0d, -71.42857142857143d);
            poseStack.mulPose(Axis.YP.rotationDegrees(60.0f + ((timeSinceLastAction + partialTick) * 48.0f)));
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0f));
        }

        
        SlashBladeCompat.renderBladePart(stack, wavefront, partName, texture, poseStack, bufferSource, packedLight);
        SlashBladeCompat.renderBladePartLuminous(stack, wavefront, partName + "_luminous", texture, poseStack, bufferSource, packedLight);

        poseStack.popPose();
    }

    
    public static void renderRightWaist(AnimatedGeoModel model, PoseStack poseStack, MultiBufferSource bufferSource,
                                        int packedLight, ItemStack stack) {
        if (!SlashBladeCompat.isSlashBladeItem(stack)) return;

        poseStack.pushPose();
        if (!model.rightWaistBones().isEmpty()) {
            applyWaistBoneTransform(HumanoidArm.RIGHT, poseStack, model);
        } else {
            poseStack.translate(0.25d, 1.25d, 0.0d);
            poseStack.mulPose(Axis.XP.rotationDegrees(5.0f));
        }
        poseStack.translate(0.0d, 0.0d, -0.7d);
        poseStack.scale(0.01f, 0.01f, 0.01f);
        poseStack.mulPose(Axis.YP.rotationDegrees(-90.0f));
        poseStack.mulPose(Axis.ZP.rotationDegrees(180.0f));
        renderBladeOnly(poseStack, bufferSource, packedLight, stack);
        poseStack.popPose();
    }

    
    private static void applyWaistBoneTransform(HumanoidArm arm, PoseStack poseStack, AnimatedGeoModel model) {
        if (arm == HumanoidArm.LEFT) {
            RenderUtils.prepMatrixForLocator(poseStack, model.leftWaistBones());
        } else {
            RenderUtils.prepMatrixForLocator(poseStack, model.rightWaistBones());
        }
    }

    
    private static boolean isBoneHidden(IBone bone) {
        return bone.getScaleX() == 0.0f && bone.getScaleY() == 0.0f && bone.getScaleZ() == 0.0f;
    }
}
