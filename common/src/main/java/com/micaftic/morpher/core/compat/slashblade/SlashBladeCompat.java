package com.micaftic.morpher.core.compat.slashblade;

import com.micaftic.morpher.client.animation.molang.CtrlBinding;
import com.micaftic.morpher.client.entity.LivingAnimatable;
import com.micaftic.morpher.geckolib3.core.builder.ILoopType;
import com.micaftic.morpher.geckolib3.core.event.predicate.AnimationEvent;
import com.micaftic.morpher.geckolib3.core.molang.context.IContext;
import com.micaftic.morpher.geckolib3.core.molang.util.StringPool;
import com.micaftic.morpher.geckolib3.core.enums.PlayState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

public final class SlashBladeCompat {

    private SlashBladeCompat() {
    }

    
    private static volatile boolean initialized = false;
    private static volatile boolean loaded = false;

    // Class
    private static Class<?> bladeStateAccessCls;          // BladeStateAccess
    private static Class<?> islashBladeStateCls;          // ISlashBladeState
    private static Class<?> itemSlashBladeCls;            // ItemSlashBlade
    private static Class<?> comboStateRegistryCls;        // ComboStateRegistry
    private static Class<?> comboStateCls;                // ComboState

    
    private static Method mBladeStateAccessOf;            // Optional<ISlashBladeState> of(ItemStack)

    
    private static Method mIsBroken;                      // boolean isBroken()
    private static Method mGetLastActionTime;             // long getLastActionTime()
    private static Method mGetComboSeq;                   // ResourceLocation getComboSeq()
    private static Method mGetComboRoot;                  // ResourceLocation getComboRoot()
    private static Method mGetTexture;                    // Optional<ResourceLocation> getTexture()
    private static Method mGetModel;                      // Optional<ResourceLocation> getModel()

    
    private static Method mComboStateRegistryGet;         // static Registry<ComboState> get()
    private static Method mRegistryGetValue;              
    private static Method mComboStateGetTimeoutMs;        // int getTimeoutMS()
    private static Method mComboStateGetName;             // String getName()

    
    private static Method mRegistryGetValueFallback;

    
    private static Field fComboStateRegistry;

    
    private static Class<?> bladeModelManagerCls;        // BladeModelManager
    private static Class<?> bladeRenderStateCls;          // BladeRenderState
    private static Class<?> wavefrontObjectCls;           // WavefrontObject

    private static Method mBladeModelManagerGetInstance;  // static BladeModelManager getInstance()
    private static Method mBladeModelManagerGetModel;     // WavefrontObject getModel(ResourceLocation)
    private static Method mRenderOverrided;                // static void renderOverrided(...)
    private static Method mRenderOverridedLuminous;        // static void renderOverridedLuminous(...)

    
    public static final String DEFAULT_BLADE_OBJ = "slashblade:model/blade.obj";
    public static final String DEFAULT_BLADE_TEXTURE = "slashblade:model/blade.png";

    
    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        
        try {
            itemSlashBladeCls = Class.forName("mods.flammpfeil.slashblade.item.ItemSlashBlade");
            bladeStateAccessCls = Class.forName("mods.flammpfeil.slashblade.capability.slashblade.BladeStateAccess");
            islashBladeStateCls = Class.forName("mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState");

            mBladeStateAccessOf = bladeStateAccessCls.getMethod("of", ItemStack.class);

            mIsBroken = islashBladeStateCls.getMethod("isBroken");
            mGetLastActionTime = islashBladeStateCls.getMethod("getLastActionTime");
            mGetComboSeq = islashBladeStateCls.getMethod("getComboSeq");
            mGetComboRoot = islashBladeStateCls.getMethod("getComboRoot");
            mGetTexture = islashBladeStateCls.getMethod("getTexture");
            mGetModel = islashBladeStateCls.getMethod("getModel");

            loaded = true;
        } catch (Throwable t) {
            loaded = false;
            return;
        }

        
        try {
            comboStateRegistryCls = Class.forName("mods.flammpfeil.slashblade.registry.ComboStateRegistry");
            comboStateCls = Class.forName("mods.flammpfeil.slashblade.registry.combo.ComboState");

            
            fComboStateRegistry = comboStateRegistryCls.getField("REGISTRY");
            mRegistryGetValue = tryFindRegistryGetMethod();
            mComboStateGetTimeoutMs = comboStateCls.getMethod("getTimeoutMS");
            mComboStateGetName = comboStateCls.getMethod("getName");

        } catch (Throwable t) {
            
        }

        
        try {
            bladeModelManagerCls = Class.forName("mods.flammpfeil.slashblade.client.renderer.model.BladeModelManager");
            bladeRenderStateCls = Class.forName("mods.flammpfeil.slashblade.client.renderer.util.BladeRenderState");
            wavefrontObjectCls = Class.forName("mods.flammpfeil.slashblade.client.renderer.model.obj.WavefrontObject");

            mBladeModelManagerGetInstance = bladeModelManagerCls.getMethod("getInstance");
            mBladeModelManagerGetModel = bladeModelManagerCls.getMethod("getModel", ResourceLocation.class);

            mRenderOverrided = bladeRenderStateCls.getMethod("renderOverrided",
                    ItemStack.class, wavefrontObjectCls, String.class, ResourceLocation.class,
                    PoseStack.class, MultiBufferSource.class, int.class);

            mRenderOverridedLuminous = bladeRenderStateCls.getMethod("renderOverridedLuminous",
                    ItemStack.class, wavefrontObjectCls, String.class, ResourceLocation.class,
                    PoseStack.class, MultiBufferSource.class, int.class);

        } catch (Throwable t) {
        }
    }

    
    private static Method tryFindRegistryGetMethod() {
        try {
            // NeoForge Registry (1.21.1)
            Class<?> registryClass = Class.forName("net.minecraft.core.Registry");
            return registryClass.getMethod("get", net.minecraft.resources.ResourceLocation.class);
        } catch (Throwable ignored) {
        }
        try {
            
            Class<?> forgeRegistry = Class.forName("net.minecraftforge.registries.IForgeRegistry");
            return forgeRegistry.getMethod("getValue", net.minecraft.resources.ResourceLocation.class);
        } catch (Throwable ignored) {
        }
        return null;
    }

    

    public static boolean isLoaded() {
        if (!initialized) init();
        return loaded;
    }

    
    public static boolean isSlashBladeItem(ItemStack itemStack) {
        if (!isLoaded() || itemStack == null || itemStack.isEmpty()) return false;
        try {
            if (itemSlashBladeCls.isInstance(itemStack.getItem())) return true;
        } catch (Throwable ignored) {
        }
        try {
            Object opt = mBladeStateAccessOf.invoke(null, itemStack);
            if (opt instanceof Optional<?> o) return o.isPresent();
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean hasNewApi() {
        return isLoaded(); 
    }

    
    public static Object getBladeState(ItemStack stack) {
        if (!isLoaded() || stack == null) return null;
        try {
            Object opt = mBladeStateAccessOf.invoke(null, stack);
            if (opt instanceof Optional<?> o) return o.orElse(null);
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static ResourceLocation getBladeModel(Object bladeState) {
        if (!isLoaded() || bladeState == null) return null;
        try {
            Object opt = mGetModel.invoke(bladeState);
            if (opt instanceof Optional<?> o) return (ResourceLocation) o.orElse(null);
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static ResourceLocation getBladeTexture(Object bladeState) {
        if (!isLoaded() || bladeState == null) return null;
        try {
            Object opt = mGetTexture.invoke(bladeState);
            if (opt instanceof Optional<?> o) return (ResourceLocation) o.orElse(null);
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean isBladeBroken(Object bladeState) {
        if (!isLoaded() || bladeState == null) return false;
        try {
            return (boolean) mIsBroken.invoke(bladeState);
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static long getBladeLastActionTime(Object bladeState) {
        if (!isLoaded() || bladeState == null) return 0;
        try {
            return (long) mGetLastActionTime.invoke(bladeState);
        } catch (Throwable ignored) {
        }
        return 0;
    }

    public static ResourceLocation getComboSeq(Object bladeState) {
        if (!isLoaded() || bladeState == null) return null;
        try {
            return (ResourceLocation) mGetComboSeq.invoke(bladeState);
        } catch (Throwable ignored) {
        }
        return null;
    }

    
    public static int getComboTimeoutMs(ResourceLocation comboSeq) {
        if (!isLoaded() || comboSeq == null || fComboStateRegistry == null) return 3000;
        try {
            Object registry = fComboStateRegistry.get(null);
            if (registry == null || mRegistryGetValue == null) return 3000;
            Object comboState = mRegistryGetValue.invoke(registry, comboSeq);
            if (comboState != null) {
                return (int) mComboStateGetTimeoutMs.invoke(comboState);
            }
        } catch (Throwable ignored) {
        }
        return 3000;
    }

    public static String getComboName(ResourceLocation comboSeq) {
        if (comboSeq == null) return StringPool.EMPTY;
        String name = comboSeq.toString();
        if (name.startsWith("ex_")) name = name.substring(3);
        return name;
    }

    

    
    public static String getComboAnimName(AnimationEvent<? extends LivingAnimatable<?>> event) {
        if (!isLoaded() || event == null) return StringPool.EMPTY;
        LivingEntity entity = event.getAnimatable().getEntity();
        if (entity == null) return StringPool.EMPTY;
        ItemStack mainHand = entity.getMainHandItem();
        if (!isSlashBladeItem(mainHand)) return StringPool.EMPTY;

        Object bladeState = getBladeState(mainHand);
        if (bladeState == null) return StringPool.EMPTY;

        long gameTime = entity.level().getGameTime();
        long lastAction = getBladeLastActionTime(bladeState);
        long elapsedMs = (gameTime - lastAction) * 50;

        ResourceLocation comboSeq = getComboSeq(bladeState);
        if (comboSeq == null) return StringPool.EMPTY;

        int timeoutMs = getComboTimeoutMs(comboSeq);
        if ("slashblade:standby".equals(comboSeq.toString())) {
            timeoutMs -= 553;
        }
        if (elapsedMs > timeoutMs) return StringPool.EMPTY;

        String name = getComboName(comboSeq);
        
        if ("slashblade:judgement_cut".equals(name) && !entity.onGround()) {
            name = "slashblade:judgement_cut_slash_air";
        } else if ("slashblade:judgement_cut_slash_just2".equals(name) && !entity.onGround()) {
            name = "slashblade:judgement_cut_slash_air_just2";
        }
        return name;
    }

    
    public static PlayState handleSlashBladeAnim(LivingEntity livingEntity, AnimationEvent<? extends LivingAnimatable<?>> event, String str, ILoopType loopType) {
        if (!isLoaded()) return null;
        if (!isSlashBladeItem(livingEntity.getMainHandItem())) return null;
        String slashName = "slashblade:" + str;
        if (event.getAnimatable().getAnimation(slashName) != null) {
            event.getController().setAnimation(slashName, loopType);
            return PlayState.CONTINUE;
        }
        if (event.getAnimatable().getAnimation(str) != null) {
            event.getController().setAnimation(str, loopType);
            return PlayState.CONTINUE;
        }
        return null;
    }

    
    public static void registerControllerFunctions(CtrlBinding ctrlBinding) {
        ctrlBinding.livingEntityVar("slashblade_animation", ctx -> getMolangComboName(ctx));
    }

    
    private static String getMolangComboName(IContext<? extends LivingEntity> context) {
        LivingEntity entity = context.entity();
        if (entity == null) return StringPool.EMPTY;
        ItemStack mainHand = entity.getMainHandItem();
        if (!isSlashBladeItem(mainHand)) return StringPool.EMPTY;

        Object bladeState = getBladeState(mainHand);
        if (bladeState == null) return StringPool.EMPTY;

        long gameTime = entity.level().getGameTime();
        long lastAction = getBladeLastActionTime(bladeState);
        long elapsedMs = (gameTime - lastAction) * 50;

        ResourceLocation comboSeq = getComboSeq(bladeState);
        if (comboSeq == null) return StringPool.EMPTY;

        int timeoutMs = getComboTimeoutMs(comboSeq);
        if ("slashblade:standby".equals(comboSeq.toString())) {
            timeoutMs -= 553;
        }
        if (elapsedMs > timeoutMs) return StringPool.EMPTY;

        return getComboName(comboSeq);
    }

    

    
    public static Object getBladeModelManager() {
        if (!isLoaded() || mBladeModelManagerGetInstance == null) return null;
        try {
            return mBladeModelManagerGetInstance.invoke(null);
        } catch (Throwable ignored) {
        }
        return null;
    }

    
    public static Object loadWavefrontModel(ResourceLocation modelId) {
        if (!isLoaded() || mBladeModelManagerGetModel == null) return null;
        Object mgr = getBladeModelManager();
        if (mgr == null) return null;
        try {
            return mBladeModelManagerGetModel.invoke(mgr, modelId);
        } catch (Throwable ignored) {
        }
        return null;
    }

    
    public static void renderBladePart(ItemStack stack, Object wavefrontObj, String partName,
                                       ResourceLocation texture, PoseStack poseStack,
                                       MultiBufferSource bufferSource, int packedLight) {
        if (!isLoaded() || mRenderOverrided == null || wavefrontObj == null) return;
        try {
            mRenderOverrided.invoke(null, stack, wavefrontObj, partName, texture, poseStack, bufferSource, packedLight);
        } catch (Throwable ignored) {
        }
    }

    
    public static void renderBladePartLuminous(ItemStack stack, Object wavefrontObj, String partName,
                                                ResourceLocation texture, PoseStack poseStack,
                                                MultiBufferSource bufferSource, int packedLight) {
        if (!isLoaded() || mRenderOverridedLuminous == null || wavefrontObj == null) return;
        try {
            mRenderOverridedLuminous.invoke(null, stack, wavefrontObj, partName, texture, poseStack, bufferSource, packedLight);
        } catch (Throwable ignored) {
        }
    }
}