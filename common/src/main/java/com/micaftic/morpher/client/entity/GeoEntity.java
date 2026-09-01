package com.micaftic.morpher.client.entity;

import com.micaftic.morpher.capability.PlayerCapability;
import com.micaftic.morpher.client.ClientModelManager;
import com.micaftic.morpher.audio.*;
import com.elfmcys.yesstevemodel.geckolib3.geo.render.built.GeoModel;
import com.micaftic.morpher.client.animation.molang.MolangEventDispatcher;
import com.micaftic.morpher.client.model.ModelAssembly;
import com.micaftic.morpher.core.compat.oculus.OculusCompat;
import com.micaftic.morpher.client.animation.molang.PhysicsManager;
import com.micaftic.morpher.client.animation.molang.MolangWatchRegistry;
import com.micaftic.morpher.client.animation.debug.AnimationFrameProfiler;
import com.micaftic.morpher.client.renderer.AnimationDebugOverlay;
import com.micaftic.morpher.client.renderer.ModelPreviewRenderer;
import com.micaftic.morpher.geckolib3.core.AnimatableEntity;
import com.micaftic.morpher.geckolib3.core.event.predicate.AnimationEvent;
import com.micaftic.morpher.geckolib3.core.molang.value.IValue;
import com.micaftic.morpher.geckolib3.core.processor.AnimationProcessor;
import com.micaftic.morpher.util.*;
import com.micaftic.morpher.util.log.ChatLogger;
import com.micaftic.morpher.util.log.ILogger;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;

public abstract class GeoEntity<T extends Entity> extends AnimatableEntity<T> {
    private String modelId;

    private ModelAssembly modelAssembly;

    private ModelWrapper renderShape;

    private boolean loaded;

    private int updateTicks;

    @Nullable
    private PhysicsManager bones;

    @Nullable
    private PhysicsManager previewBones;

    @Nullable
    private PhysicsManager extraPlayerBones;

    @Nullable
    private MolangWatchRegistry boneLookup;

    @Nullable
    private List<IValue> renderLayers;

    @Nullable
    private Future<AnimationEvent<?>> modelFuture;

    private int asyncSubmitFrameId = -1;

    @Nullable
    public abstract GeoEntity.ModelWrapper buildRenderShape(ModelAssembly modelAssembly, boolean isDefault);

    public abstract GeoModel getAnimationProcessor();

    public GeoEntity(T t, boolean registerWithCache) {
        super(t);
        this.modelId = "default";
        if (registerWithCache) {
            EntityRenderCache.register(this);
        }
    }

    @Override
    public PhysicsManager getPhysicsManager() {
        if (ModelPreviewRenderer.isPreview()) {
            if (this.previewBones == null) {
                this.previewBones = new PhysicsManager();
            }
            return this.previewBones;
        }
        if (com.micaftic.morpher.client.render.RenderContext.isGuiPreview()) {
            if (this.extraPlayerBones == null) {
                this.extraPlayerBones = new PhysicsManager();
            }
            return this.extraPlayerBones;
        }
        if (ModelPreviewRenderer.isFirstPerson()) {
            return this.physicsManager;
        }
        if (this.bones == null) {
            this.bones = new PhysicsManager();
        }
        return this.bones;
    }

    @Nullable
    public List<IValue> getRenderLayers() {
        return this.renderLayers;
    }

    public void setBoneLookup(@Nullable MolangWatchRegistry watchRegistry) {
        this.boneLookup = watchRegistry;
    }

    @Override
    public void setupAnim(float seekTime, boolean isFirstPerson) {
        super.setupAnim(seekTime, isFirstPerson);
        if (this.boneLookup != null) {
            AnimationProcessor<T> processor = getEvaluationContext();
            processor.execute(evaluator -> {
                this.boneLookup.evauatePreAnimation(evaluator);
                return null;
            }, false, true, null);
            processor.execute(it -> {
                this.boneLookup.evaluatePostAnimation(it);
                return null;
            }, false, false, null);
        }
    }

    public void tickModel() {
        if (this.updateTicks < this.entity.tickCount) {
            refreshModel();
            this.updateTicks = this.entity.tickCount;
        }
    }

    public final ModelAssembly getModelAssembly() {
        return this.modelAssembly;
    }

    public final boolean referencesModelAssembly(ModelAssembly assembly) {
        return assembly != null && (this.modelAssembly == assembly
                || (this.renderShape != null && this.renderShape.context == assembly));
    }

    public final void setModelId(String str) {
        if (java.util.Objects.equals(this.modelId, str)) {
            return;
        }
        this.modelId = str;
        refreshModel();
    }

    public final void forceReloadModel(String str) {
        this.modelId = str;
        refreshModel();
    }

    private void refreshModel() {
        // Step 1: try the requested modelId first
        Optional<ModelAssembly> requested = ClientModelManager.getModelContext(this.modelId);
        if (requested.isPresent()) {
            updateRenderShape(requested.get(), false);
        } else {
            // Requested model not ready yet. Be conservative:
            // - If it's still loading and we already have a model, KEEP the existing one
            //   (avoid resetting animation controllers every tick during async load).
            // - Only fall back if we have nothing at all, or if the modelId is definitively unknown.
            boolean pending = ClientModelManager.isModelLoadPending(this.modelId);
            if (pending && hasRenderableModel()) {
                // Still loading + we have something to show → keep what we have
                return;
            }
            if (pending && !hasRenderableModel()) {
                // Still loading but nothing to show yet → also wait, do NOT clear
                return;
            }
            // ModelId is not pending AND not loaded → it's definitively unknown.
            // Fall back to local default instead of hard-clearing (prevents flicker).
            ModelAssembly fallback = ClientModelManager.getLocalModelContext();
            if (fallback != null && fallback.isRuntimeResident()) {
                updateRenderShape(fallback, true);
            } else if (this.renderShape != null || this.modelAssembly != null) {
                // Last resort: default model also unavailable, only then clear
                clearModel();
                return;
            } else {
                return;
            }
        }

        // Step 2: if a renderShape now exists, reconcile state
        if (this.renderShape != null) {
            if (this.renderShape.isValid()
                    && (this.renderShape.context != this.modelAssembly
                        || this.renderShape.isDefault != this.loaded)) {
                this.modelAssembly = this.renderShape.context;
                this.loaded = this.renderShape.isDefault;
                onModelLoaded(this.modelAssembly);
                initAnimationControllers(getAnimationProcessor(), this.modelAssembly.getExpressionCache().getEvents());
            }
            // No change to modelAssembly → keep existing animation controllers intact
            return;
        }
        // renderShape somehow disappeared — only clear if we had something before
        if (this.modelAssembly != null) {
            clearModel();
        }
    }

    private void updateRenderShape(ModelAssembly assembly, boolean isDefault) {
        synchronized (assembly) {
            if (!assembly.isRuntimeResident()) {
                return;
            }
            if (this.renderShape == null
                    || this.renderShape.isDefault != isDefault
                    || assembly != this.renderShape.context) {
                this.renderShape = buildRenderShape(assembly, isDefault);
            }
        }
    }

    public final ModelWrapper getRenderShape() {
        return this.renderShape;
    }

    public void onModelLoaded(ModelAssembly modelAssembly) {
        this.renderShape.audioProvider = AudioStreamCache.getOrCreateProvider(modelAssembly);
        this.renderLayers = modelAssembly.getExpressionCache().getEvents().get(MolangEventDispatcher.DEFER);
    }

    public void clearModel() {
        this.modelAssembly = null;
        this.renderLayers = null;
        this.renderShape = null;
        this.loaded = false;
        reset();
    }

    @Override
    public void reset() {
        if (this.modelFuture != null) {
            awaitAsyncResult();
        }
        super.reset();
        this.bones = null;
        this.previewBones = null;
        this.extraPlayerBones = null;
        this.modelFuture = null;
        this.asyncSubmitFrameId = -1;
        this.updateTicks = 0;
    }

    public void resetModel() {
        this.modelId = "default";
        this.modelInitialized = false;
        clearModel();
    }

    public final String getModelId() {
        return this.modelId;
    }

    public boolean isModelReady() {
        return this.renderShape != null
                && !this.renderShape.isDefault
                && this.renderShape.context.isRuntimeResident()
                && this.renderShape.isValid();
    }

    public boolean hasRenderableModel() {
        return this.modelAssembly != null
                && this.renderShape != null
                && this.renderShape.context.isRuntimeResident()
                && this.renderShape.isValid();
    }

    @Override
    public boolean shouldSkipAnimation(AnimationEvent<?> event) {
        // Never skip animations here — only PlayerCapability (via CustomPlayerEntity) has a
        // legitimate reason to skip (local-player first-person, where PlayerGeoEntity drives arms).
        // The base classes must evaluate so remote entities and non-player animators keep ticking.
        return OculusCompat.isPBRActive();
    }

    @Override
    @Nullable
    public final IValue resolveExpression(String str) {
        return getModelAssembly().getExpressionCache().getFunctions().get(str);
    }

    @Override
    public Optional<IAudioStreamFactory> getAudioStreamFactory(String str) {
        AudioTrackData trackData;
        if (this.renderShape.audioProvider != null && (trackData = getModelAssembly().getExpressionCache().getSoundEffects().get(str)) != null && trackData.getData() != null && trackData.getCodec() != AudioCodec.UNDEFINED) {
            IAudioStreamProvider streamProvider = this.renderShape.audioProvider;
            return Optional.of(() -> {
                return streamProvider.createAudioStream(trackData);
            });
        }
        return Optional.empty();
    }

    @Override
    public ILogger getLogger() {
        if (AnimationDebugOverlay.isDebugActive()) {
            return ChatLogger.INSTANCE;
        }
        return null;
    }

    public void submitAsyncUpdate(float partialTick) {
        // Capture the animation time base on the render thread. The worker must not read
        // entity.tickCount at execution time: a delayed/culled task would compute a time
        // that is ahead of its submission frame, making seekTime advance in bursts followed
        // by freezes (visible as ~20Hz stutter on other players' models).
        int capturedTickCount = this.entity.tickCount;
        int renderFrameId = AnimationFrameProfiler.getRenderFrameId();
        UnsafeUtil.getUnsafe().storeFence();
        this.asyncSubmitFrameId = renderFrameId;
        this.modelFuture = YSMThreadPool.submitCallable(() -> {
            PlayerCapability playerCapability = this instanceof PlayerCapability cap ? cap : null;
            if (playerCapability != null) {
                playerCapability.beginCapturedRenderState();
            }
            try {
                AnimationEvent<?> event = super.processAnimationImpl(partialTick, capturedTickCount, false);
                UnsafeUtil.getUnsafe().storeFence();
                return event;
            } catch (Throwable th) {
                UnsafeUtil.getUnsafe().storeFence();
                throw th;
            } finally {
                if (playerCapability != null) {
                    playerCapability.endRenderState();
                }
            }
        });
    }

    public boolean hasPendingAsyncUpdate() {
        return this.modelFuture != null;
    }

    @Override
    @Nullable
    public AnimationEvent<?> processAnimationImpl(float partialTick, boolean isFirstPerson) {
        RenderSystem.assertOnRenderThread();
        if (!isModelReady()) {
            if (this.modelFuture != null) {
                awaitAsyncResult();
            }
            return null;
        }
        boolean isGuiPreview = ModelPreviewRenderer.isPreview() || com.micaftic.morpher.client.render.RenderContext.isGuiPreview();
        if (isGuiPreview || this instanceof com.micaftic.morpher.capability.PlayerCapability) {
            if (this.modelFuture != null) {
                awaitAsyncResult();
            }
            return super.processAnimationImpl(partialTick, isFirstPerson);
        }
        // First-person arm (PlayerGeoEntity): async pipeline, submit only while actually rendered
        int renderFrameId = AnimationFrameProfiler.getRenderFrameId();
        if (this.modelFuture != null && this.asyncSubmitFrameId != renderFrameId) {
            // The pending task belongs to a frame in which this entity was not rendered;
            // its time base is stale. Discard it and submit a fresh task below.
            awaitAsyncResult();
        }
        if (this.modelFuture == null && this.asyncSubmitFrameId != renderFrameId) {
            submitAsyncUpdate(partialTick);
        }
        if (this.modelFuture != null) {
            AnimationEvent<?> event = awaitAsyncResult();
            if (event != null) {
                return event;
            }
        }
        return super.processAnimationImpl(partialTick, isFirstPerson);
    }

    public AnimationEvent<?> awaitAsyncResult() {
        if (this.modelFuture != null) {
            AnimationEvent<?> event = null;
            try {
                event = this.modelFuture.get();
                UnsafeUtil.getUnsafe().loadFence();
            } catch (InterruptedException e) {
            } catch (Throwable th) {
                th.printStackTrace();
            }
            this.modelFuture = null;
            return event;
        }
        return null;
    }

    public boolean supportsAsync() {
        return true;
    }

    public static class ModelWrapper {

        public final ModelAssembly context;

        public final boolean isDefault;

        @Nullable
        public IAudioStreamProvider audioProvider;

        public ModelWrapper(ModelAssembly modelAssembly, boolean isDefault) {
            this.context = modelAssembly;
            this.isDefault = isDefault;
        }

        public boolean isValid() {
            return true;
        }
    }
}
