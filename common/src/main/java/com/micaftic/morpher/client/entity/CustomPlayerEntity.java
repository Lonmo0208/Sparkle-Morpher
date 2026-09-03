package com.micaftic.morpher.client.entity;

import com.micaftic.morpher.YesSteveModel;
import com.micaftic.morpher.geckolib3.core.controller.controllers.UnifiedPlayerActionController;
import com.micaftic.morpher.client.animation.molang.MolangEventDispatcher;
import com.micaftic.morpher.client.model.ModelAssembly;
import com.micaftic.morpher.core.compat.oculus.OculusCompat;
import com.micaftic.morpher.geckolib3.core.event.predicate.AnimationEvent;
import com.micaftic.morpher.geckolib3.core.molang.value.IValue;
import com.micaftic.morpher.geckolib3.core.enums.AnimationState;
import com.micaftic.morpher.resource.models.ModelProperties;
import com.micaftic.morpher.molang.runtime.Struct;
import com.micaftic.morpher.network.NetworkHandler;
import com.micaftic.morpher.network.message.C2SPlayAnimationPacket;
import com.micaftic.morpher.util.AnimationRouletteDebugLog;
import com.micaftic.morpher.util.data.OrderedStringMap;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

public abstract class CustomPlayerEntity extends LivingAnimatable<Player> implements RoamingPropertyHolder {

    public final boolean isLocalPlayer;

    public boolean isModelSwitching;

    public String selectedModelId;

    public boolean isDisabled;

    /** 尚未成功解析的轮盘动画请求（延迟重试，直到模型就绪/动画名可解析）。 */
    private String pendingModelSwitch;

    /** 轮盘动画是否已经真正开始播放过（区分"从未播放"与"播放完回到 IDLE"）。 */
    private boolean switchAnimationStarted;

    private List<IValue> syncIValues;

    public CustomPlayerEntity(Player player, boolean isLocalPlayer, boolean isActive) {
        super(player, isActive);
        this.isModelSwitching = false;
        this.selectedModelId = "idle";
        this.isDisabled = false;
        this.pendingModelSwitch = null;
        this.switchAnimationStarted = false;
        this.syncIValues = null;
        this.isLocalPlayer = isLocalPlayer;
        if (player instanceof LocalPlayer) {
            markModelInitialized();
        }
    }

    @Override
    public void registerAnimationControllers() {
        getModelAssembly().getAnimationBundle().getPlayerControllerInstaller().accept(this);
    }

    @Override
    public void resetModel() {
        super.resetModel();
        this.syncIValues = null;
    }

    @Override
    public void reset() {
        super.reset();
        this.isModelSwitching = false;
        this.selectedModelId = "idle";
        this.isDisabled = false;
        this.pendingModelSwitch = null;
        this.switchAnimationStarted = false;
    }

    @Override
    public boolean shouldSkipAnimation(AnimationEvent<?> event) {
        return OculusCompat.isPBRActive();
    }

    @Override
    @Nullable
    public Struct getServerVarContainer() {
        return null;
    }

    public boolean isLocalPlayerModel() {
        return this.isLocalPlayer;
    }

    @Override
    public void onModelLoaded(ModelAssembly context) {
        super.onModelLoaded(context);
        this.syncIValues = context.getExpressionCache().getEvents().get(MolangEventDispatcher.SYNC);
    }

    public void requestModelSwitch(String str) {
        String animationName = resolvePlayableAnimation(str);
        if (animationName != null) {
            AnimationRouletteDebugLog.info("client playback request={} resolved={} fallback={}",
                    str, animationName, !animationName.equals(str));
            this.selectedModelId = animationName;
            this.isModelSwitching = true;
            this.isDisabled = true;
            this.switchAnimationStarted = false;
            this.pendingModelSwitch = null;
            return;
        }
        // 轮盘发来的动画 key 在当前已加载模型的动画列表中暂时不存在：
        // 可能是模型还在异步加载、或服务端/客户端模型定义短暂不一致。
        // 不再静默放弃，而是保留为 pending，后续帧模型就绪后再重试解析。
        this.pendingModelSwitch = str;
        if (AnimationRouletteDebugLog.enabled() && str != null && !str.isBlank() && !"idle".equals(str)) {
            try {
                YesSteveModel.LOGGER.warn(
                        "[SM] 轮盘动画 '{}' 当前无法解析，已挂起等待模型就绪后重试；该模型可用动画: {}",
                        str, getModelAssembly().getAnimationBundle().getMainAnimations().keySet());
            } catch (Exception ignored) {
            }
        }
    }

    private @Nullable String resolvePlayableAnimation(String animationName) {
        if (animationName == null || animationName.isBlank()) {
            return null;
        }
        if (getAnimation(animationName) != null) {
            return animationName;
        }
        ModelProperties properties = getModelAssembly().getModelData().getModelProperties();
        String resolved = resolveExtraAnimationValue(properties.getExtraAnimation(), animationName);
        if (resolved != null) {
            return resolved;
        }
        for (OrderedStringMap<String, String> group : properties.getExtraAnimationClassify().values()) {
            resolved = resolveExtraAnimationValue(group, animationName);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private @Nullable String resolveExtraAnimationValue(OrderedStringMap<String, String> animations, String key) {
        if (animations == null || key == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : animations.entrySet()) {
            if (key.equals(entry.getKey())) {
                String value = entry.getValue();
                return value != null && getAnimation(value) != null ? value : null;
            }
        }
        return null;
    }

    public void enableModel() {
        this.isDisabled = false;
    }

    public boolean isModelSwitching() {
        return this.isModelSwitching;
    }

    public boolean isDisabledState() {
        return this.isDisabled;
    }

    public String getSelectedModelId() {
        return this.selectedModelId;
    }

    public void clearModelSwitch() {
        this.isModelSwitching = false;
        this.switchAnimationStarted = false;
        this.pendingModelSwitch = null;
    }

    @Override
    public void setupAnim(float seekTime, boolean isFirstPerson) {
        super.setupAnim(seekTime, isFirstPerson);
        getEvaluationContext().setRoamingProperties(getServerVarContainer());
        // 模型就绪后重试挂起的轮盘动画请求（模型异步加载 / 服务端模型短暂不一致场景）。
        if (this.pendingModelSwitch != null) {
            String animationName = resolvePlayableAnimation(this.pendingModelSwitch);
            if (animationName != null) {
                AnimationRouletteDebugLog.info("client pending playback resolved={} requested={}",
                        animationName, this.pendingModelSwitch);
                this.selectedModelId = animationName;
                this.isModelSwitching = true;
                this.isDisabled = true;
                this.switchAnimationStarted = false;
                this.pendingModelSwitch = null;
            }
        }
    }

    @Override
    public void afterSetupAnim(float seekTime, boolean isFirstPerson) {
        super.afterSetupAnim(seekTime, isFirstPerson);
        // 只在轮盘动画确实播放过、且播放完毕回到 IDLE 时才清状态并通知服务器停止。
        // 若动画从未开始（switchAnimationStarted 仍为 false），不能清状态/发停止包，
        // 否则点击后动画尚未真正播放就被自己掐断（表现为"要点好几次才播"）。
        if (this.isLocalPlayer && isFirstPerson && isModelSwitching() && this.switchAnimationStarted
                && getAnimationState(getCapControllerKey()) == AnimationState.IDLE) {
            clearModelSwitch();
            if (NetworkHandler.isClientConnected()) {
                NetworkHandler.sendToServer(C2SPlayAnimationPacket.createDefault());
            }
        }
    }

    /** 标记轮盘动画已真正开始播放（由播放 predicate 在成功启动动画时调用）。 */
    public void markSwitchAnimationStarted() {
        this.switchAnimationStarted = true;
    }

    private String getCapControllerKey() {
        return UnifiedPlayerActionController.CAP_CONTROLLER_KEY;
    }

    public void executeAnimationExpression(FloatArrayList floatArrayList) {
        if (this.syncIValues != null) {
            executeExpression(MolangEventDispatcher.createExpression(this.syncIValues, floatArrayList), true, false, null);
        }
    }
}
