package com.micaftic.morpher.client.event;

import com.micaftic.morpher.YesSteveModel;
import com.micaftic.morpher.capability.PlayerCapability;
import com.micaftic.morpher.capability.client.PlayerCapabilityClientStore;
import com.micaftic.morpher.client.renderer.ModelPreviewRenderer;
import com.micaftic.morpher.client.render.PlayerRenderPolicy;
import com.micaftic.morpher.client.renderer.RendererManager;
import com.micaftic.morpher.core.config.ConfigPolicies;
import com.micaftic.morpher.util.CameraUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import com.micaftic.morpher.core.compat.firstperson.FirstPersonCompat;
import com.micaftic.morpher.core.compat.playeranimator.PlayerAnimatorCompat;
import com.micaftic.morpher.core.compat.realcamera.RealCameraCompat;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ReplacePlayerRenderEvent {

    /** 真人实体仍可被我们渲染的距离上限（玩家实体客户端跟踪距离 32 区块）。 */
    static final double FAR_MODEL_DISTANCE_SQR = 32.0D * 16.0D * 32.0D * 16.0D;

    /**
     * 本帧原版实体通道已经交给 morpher 处理的玩家实体 ID（远景补渲染据此去重）。
     *
     * <p>远景补渲染只补「原版这一帧没画到」的玩家，所以不必去复刻原版的整条可见性判定：
     * 其他玩家的剔除线是 {@code RemotePlayer.shouldRenderAtSqrDistance}（包围盒 ×10 ×64，
     * 与本机视距挂钩），还要再叠视锥与 {@code LevelRenderer.isSectionCompiled}（区块未编译
     * 的位置不提交实体渲染）。原版到底因为哪一条没画并不重要——只要本帧没进过这个集合，
     * 就说明模型还没人画。</p>
     *
     * <p>只在渲染线程读写。</p>
     */
    private static final IntOpenHashSet HANDLED_THIS_FRAME = new IntOpenHashSet();

    /**
     * 本帧已经画过（或已确定由谁画）的「源头玩家」UUID。
     *
     * <p>同一个真玩家可能同时存在多个远景替身：VSS 的 {@code VSSRemotePlayer} 与 voxy 的
     * {@code PlayerProxy} 各建一个，两者的插值位置还差几格。真人实体超出客户端跟踪距离后，
     * 两路替身都会拿到同一个模型能力，各画一次就是「重影」。所以按源头 UUID 认领：一帧内
     * 只让第一个拿走，其余替身取消渲染。</p>
     *
     * <p>只在渲染线程读写。</p>
     */
    private static final Set<UUID> RENDERED_SOURCES_THIS_FRAME = new HashSet<>();

    /** 远处模型是否关掉深度比较：命令的运行时覆盖优先，null 表示取配置 {@code FarModelNoDepthTest}。 */
    @Nullable
    private static volatile Boolean FAR_MODEL_NO_DEPTH_OVERRIDE;

    private ReplacePlayerRenderEvent() {
    }

    /** 本帧原版实体通道是否已经处理过该玩家（无论最终画的是模型还是原版皮肤）。 */
    static boolean wasHandledThisFrame(Player player) {
        return HANDLED_THIS_FRAME.contains(player.getId());
    }

    /**
     * 认领本帧该源头玩家的渲染。
     *
     * @return true 表示这一帧已经有人画过这个玩家，调用方应取消自己的那次渲染
     */
    static boolean claimRender(UUID sourceUuid) {
        return sourceUuid != null && !RENDERED_SOURCES_THIS_FRAME.add(sourceUuid);
    }

    /** 由 {@code FarPlayerModelRenderEvent} 在帧尾（AFTER_LEVEL）调用，清空两份当帧登记。 */
    static void endFrame() {
        HANDLED_THIS_FRAME.clear();
        RENDERED_SOURCES_THIS_FRAME.clear();
    }

    public static boolean onRenderPlayerPre(Player entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (!YesSteveModel.isAvailable()) {
            return false;
        }
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (PlayerCapabilityClientStore.isFakePlayerEntity(entity)) {
            return renderProxyPlayer(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight, localPlayer);
        }
        HANDLED_THIS_FRAME.add(entity.getId());
        claimRender(entity.getUUID());
        PlayerCapability cap = PlayerCapability.get(entity).orElse(null);
        if (willRenderCustom(entity, cap, localPlayer)) {
            renderCustom(cap, entityYaw, partialTick, poseStack, bufferSource, packedLight);
            return true;
        }
        return false;
    }

    /**
     * 替身/代理玩家实体的渲染接管：voxy 的 {@code FarEntityRenderer$PlayerProxy}（实体 ID 1e9+，
     * UUID 恒为真玩家）与 VSS 的 {@code FarPlayerClientRenderer$VSSRemotePlayer}（实体 ID 约 -2e9，
     * 仅在 {@code manualFarPlayerRender} 手动远景渲染阶段把 {@code getUUID()} 暴露为真玩家 UUID，
     * 平时返回合成 UUID）。
     *
     * <p>关键点：真人实体并不总是在原版实体循环里提交渲染——其他玩家的剔除线是
     * {@code RemotePlayer.shouldRenderAtSqrDistance}（包围盒 ×10，实测远大于 64 格），之外还有视锥、
     * 以及 {@code LevelRenderer} 的 {@code isSectionCompiled} 判定（区块没被原版编译的位置整帧不提交
     * 实体）。所以「真人实体还在 {@code level.players()} 里」并不代表他这一帧会被画出来；靠
     * 「附近有没有同 UUID 的真人实体」来隐藏替身，就会出现「真人没画 + 替身被隐藏」的两头空。
     * 这里改由 {@link FarPlayerModelRenderEvent} 按「原版这一帧有没有处理过」补画真人模型，
     * 替身只在真人实体确实还在客户端时让位。</p>
     *
     * <p>替身自身的处理规则：</p>
     * <ol>
     *   <li>真人实体仍在客户端（32 区块跟踪距离内）且会用 YSM 模型 → 取消替身：这一帧模型由
     *       原版实体通道或 {@link FarPlayerModelRenderEvent} 的补渲染通道给出，同一帧只画一次；</li>
     *   <li>真人实体已被客户端移除（超出跟踪距离）、但按真 UUID 取得到模型能力 → 在替身上补渲染
     *       模型。能力会重新绑定到替身实体，姿势/行走动画跟随远景渲染器逐帧插值的替身，而不是
     *       停在真玩家最后可见的状态；同一玩家的多路替身（VSS + voxy 各建一个）按源头 UUID
     *       一帧只放行第一个，避免两个替身各画一个模型形成重影；</li>
     *   <li>取不到模型能力（合成 UUID 阶段、玩家没有 YSM 模型）→ 不接管，把原版渲染交还给
     *       远景渲染器（VSS 在原版通道里会自行取消它的那次替身渲染，手动通道会照常画皮肤）。</li>
     * </ol>
     *
     * @return true 表示取消原版渲染
     */
    private static boolean renderProxyPlayer(Player proxy, float entityYaw, float partialTick, PoseStack poseStack,
                                             MultiBufferSource bufferSource, int packedLight, LocalPlayer localPlayer) {
        UUID sourceUuid = proxy.getUUID();
        Player real = findRealPlayerEntity(sourceUuid);
        if (real != null) {
            if (real.equals(localPlayer)) {
                // 自己的替身永远不画（第一人称/第三人称都会变成「两个自己」）。
                return true;
            }
            // 真人实体还在客户端里（32 区块跟踪距离内）且会用 YSM 模型：模型由我们自己的两条通道
            // 之一给出（原版实体通道，或原版没画到时由 FarPlayerModelRenderEvent 补画），
            // 替身让位避免双模型。
            if (isWithinFarModelDistance(real) && willRenderCustom(real, PlayerCapability.get(real).orElse(null), localPlayer)) {
                return true;
            }
        }
        PlayerCapability cap = real == null
                ? PlayerCapabilityClientStore.getForFarPlayer(proxy, sourceUuid).orElse(null)
                : PlayerCapabilityClientStore.getByUuid(sourceUuid).orElse(null);
        Player policyEntity = real != null ? real : proxy;
        if (cap != null && willRenderCustom(policyEntity, cap, localPlayer)) {
            if (claimRender(sourceUuid)) {
                // 同一个玩家这一帧已经由另一个替身（或真人通道）画过 → 取消这一次，避免重影。
                return true;
            }
            PlayerCapability bound = PlayerCapabilityClientStore.getOrBindToProxy(proxy, sourceUuid).orElse(cap);
            boolean noDepth = shouldIgnoreDepthForFarDraw(cameraDistanceSqr(proxy));
            renderFarCustom(bound, entityYaw, partialTick, poseStack, bufferSource, packedLight, noDepth);
            return true;
        }
        return false;
    }

    /**
     * 按 UUID 找真实体。只有远景渲染器把替身的 {@code getUUID()} 暴露成真玩家 UUID 时（voxy 恒为
     * 真 UUID，VSS 仅在其手动远景渲染阶段）才找得到；合成 UUID 阶段找不到任何实体，属预期。
     */
    private static Player findRealPlayerEntity(UUID uuid) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || uuid == null) {
            return null;
        }
        for (AbstractClientPlayer player : level.players()) {
            if (PlayerCapabilityClientStore.isFakePlayerEntity(player)) {
                continue;
            }
            if (uuid.equals(player.getUUID())) {
                return player;
            }
        }
        return null;
    }

    /**
     * 真人实体是否仍在「我们自己会画」的距离内。玩家实体的客户端跟踪距离是 32 区块，所以这个判定
     * 基本等价于「实体还在客户端里」；超出后身份只剩替身可查，渲染只能交给替身。
     */
    static boolean isWithinFarModelDistance(Player player) {
        Minecraft minecraft = Minecraft.getInstance();
        if (player.isRemoved() || player.level() != minecraft.level) {
            return false;
        }
        return cameraDistanceSqr(player) <= FAR_MODEL_DISTANCE_SQR;
    }

    /**
     * 超出原版地形覆盖范围、且当前选的是「不做深度比较」时，远处绘制关掉深度比较
     * （见配置 {@code FarModelNoDepthTest} 与命令 {@code /ysmfardepth}）。
     */
    static boolean shouldIgnoreDepthForFarDraw(double cameraDistSqr) {
        return isFarModelNoDepthTest() && isBeyondVanillaTerrain(cameraDistSqr);
    }

    /** 远处模型是否关掉深度比较：命令的运行时覆盖优先，否则取配置。 */
    static boolean isFarModelNoDepthTest() {
        Boolean override = FAR_MODEL_NO_DEPTH_OVERRIDE;
        return override != null ? override : ConfigPolicies.farModelNoDepthTest();
    }

    /** 命令用：设置本次游戏的运行时覆盖，null 表示回到配置值。 */
    static void setFarModelNoDepthOverride(@Nullable Boolean value) {
        FAR_MODEL_NO_DEPTH_OVERRIDE = value;
    }

    @Nullable
    static Boolean getFarModelNoDepthOverride() {
        return FAR_MODEL_NO_DEPTH_OVERRIDE;
    }

    /**
     * 目标是否已超出原版地形覆盖范围（那里只有 Voxy 的 LOD 地形，主 framebuffer 的深度对它无效）。
     */
    static boolean isBeyondVanillaTerrain(double cameraDistSqr) {
        double vanillaBlocks = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0D;
        double limit = Math.max(64.0D, vanillaBlocks);
        return cameraDistSqr > limit * limit;
    }

    /** 与 {@link #onRenderPlayerPre} 中对真实体使用的门控完全一致，避免替身绕过用户配置。 */
    static boolean willRenderCustom(Player player, PlayerCapability cap, LocalPlayer localPlayer) {
        if (cap == null) {
            return false;
        }
        boolean firstPersonSuppressionSatisfied = !CameraUtil.isFirstPerson(cap)
                || FirstPersonCompat.isFirstPersonActive()
                || RealCameraCompat.isActive()
                || ConfigPolicies.render().disableExternalFirstPersonAnimation()
                || !PlayerAnimatorCompat.isPlayerAnimated(localPlayer);
        return PlayerRenderPolicy.decide(new PlayerRenderPolicy.GateInputs(
                true,
                player.equals(localPlayer),
                ConfigPolicies.render().disableSelfModel(),
                ConfigPolicies.render().disableOtherModel(),
                player.isSpectator(),
                cap.isModelActive(),
                firstPersonSuppressionSatisfied
        )) == PlayerRenderPolicy.Decision.RENDER_CUSTOM;
    }

    private static void renderCustom(PlayerCapability cap, float entityYaw, float partialTick, PoseStack poseStack,
                                     MultiBufferSource bufferSource, int packedLight) {
        float previewYaw = ModelPreviewRenderer.isInventoryPreviewFrontFacing() ? ModelPreviewRenderer.FRONT_FACING_YAW : entityYaw;
        RendererManager.getPlayerRenderer().render(cap, previewYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    /**
     * 远景绘制。{@code noDepth} 用于超出原版地形覆盖范围的远处玩家：那里主 framebuffer 的深度
     * 对 Voxy 的 LOD 地形本就无效（看不看得到都不由它决定），但模型自身面片之间仍会做深度比较，
     * 而远处深度精度随距离急剧变差（Voxy 为画 LOD 扩展了投影远平面），于是细密面片互相 z-fighting
     * 表现为闪烁、越远越明显。关掉深度比较即可消除闪烁，同时保留深度写入，后续几何仍能被模型遮挡。
     */
    private static void renderFarCustom(PlayerCapability cap, float entityYaw, float partialTick, PoseStack poseStack,
                                        MultiBufferSource bufferSource, int packedLight, boolean noDepth) {
        if (!noDepth) {
            renderCustom(cap, entityYaw, partialTick, poseStack, bufferSource, packedLight);
            return;
        }
        if (bufferSource instanceof MultiBufferSource.BufferSource source) {
            source.endBatch();
            boolean depthTestWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            RenderSystem.disableDepthTest();
            try {
                renderCustom(cap, entityYaw, partialTick, poseStack, source, packedLight);
                // 必须在状态窗口内 flush：几何只有光栅化时才会用到当前的深度状态。
                source.endBatch();
            } finally {
                if (depthTestWasEnabled) {
                    RenderSystem.enableDepthTest();
                }
            }
        } else {
            renderCustom(cap, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        }
    }

    private static double cameraDistanceSqr(Player player) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return player.distanceToSqr(camera.x, camera.y, camera.z);
    }
}
