package com.micaftic.morpher.capability.client;

import com.micaftic.morpher.YesSteveModel;
import com.micaftic.morpher.capability.PlayerCapability;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PlayerCapabilityClientStore {

    private static final ConcurrentMap<UUID, PlayerCapability> STORE = new ConcurrentHashMap<>();

    private static final int FAKE_ENTITY_ID_THRESHOLD = 1_000_000_000;

    private PlayerCapabilityClientStore() {
    }

    private static boolean isFakeId(int entityId) {
        return entityId >= FAKE_ENTITY_ID_THRESHOLD || entityId <= -FAKE_ENTITY_ID_THRESHOLD;
    }


    public static boolean isFakePlayerEntity(Player player) {
        return isFakeId(player.getId());
    }


    public static Optional<PlayerCapability> getByUuid(UUID uuid) {
        return Optional.ofNullable(STORE.get(uuid));
    }

    public static Optional<PlayerCapability> get(Player player) {
        if (!(player instanceof AbstractClientPlayer)) {
            return Optional.empty();
        }

        if (isFakeId(player.getId())) {
            return Optional.empty();
        }
        UUID uuid = player.getUUID();
        PlayerCapability existing = STORE.get(uuid);
        if (existing != null) {

            if (existing.entity != null && isFakeId(existing.entity.getId())) {
                PlayerCapability fresh = new PlayerCapability(player);
                fresh.copyFrom(existing);
                STORE.put(uuid, fresh);
                return Optional.of(fresh);
            }

            boolean crossLevel = existing.entity == null
                    || existing.entity.isRemoved()
                    || existing.entity.level() != player.level();
            if (crossLevel) {
                PlayerCapability fresh = new PlayerCapability(player);
                fresh.copyFrom(existing);
                STORE.put(uuid, fresh);
                return Optional.of(fresh);
            }

            boolean isActualLocal = player instanceof LocalPlayer && player == Minecraft.getInstance().player;
            if (isActualLocal && !existing.isLocalPlayerModel()) {
                PlayerCapability fresh = new PlayerCapability(player);
                fresh.copyFrom(existing);
                STORE.put(uuid, fresh);
                return Optional.of(fresh);
            }

            return Optional.of(existing);
        }
        PlayerCapability fresh = new PlayerCapability(player);
        STORE.put(uuid, fresh);
        return Optional.of(fresh);
    }

    /**
     * 远距离替身渲染用：按真玩家 UUID 取出已同步的模型能力，能力仍挂在已失效的实体上时
     * 把它重新绑定到替身实体。
     *
     * <p>替身（voxy {@code PlayerProxy} / VSS {@code VSSRemotePlayer}）由远景渲染器逐帧插值更新
     * 位置与动作状态。真玩家实体在 64~512 格之间仍被客户端跟踪（玩家实体跟踪距离 32 区块），
     * 此时能力挂在真实体上、姿势是最准的，直接复用即可；超过跟踪距离后真实体已被移除，能力
     * 只剩最后一次可见的姿势，此时改绑到替身实体，模型才会跟着替身动。</p>
     *
     * <p>真玩家实体重新进入渲染范围时，{@link #get(Player)} 的 fake-id 分支会自动把能力换回
     * 真玩家实体（{@code copyFrom} 迁移模型与 molang 变量），因此这里的临时绑定不会影响
     * 后续对真实体的渲染。</p>
     */
    public static Optional<PlayerCapability> getOrBindToProxy(Player proxy, UUID sourceUuid) {
        if (proxy == null || sourceUuid == null) {
            return Optional.empty();
        }
        PlayerCapability existing = STORE.get(sourceUuid);
        if (existing == null) {
            return Optional.empty();
        }
        // 能力仍绑在存活实体上（真玩家或同一玩家的另一路替身，如 voxy 与 VSS 同时开启远景渲染）
        // 就直接复用，避免多余的 copyFrom 触发模型重新初始化。
        if (existing.entity != null && !existing.entity.isRemoved()
                && existing.entity.level() == proxy.level()) {
            return Optional.of(existing);
        }
        PlayerCapability fresh = new PlayerCapability(proxy);
        fresh.copyFrom(existing);
        STORE.put(sourceUuid, fresh);
        return Optional.of(fresh);
    }

    /**
     * 远景替身专用：按真玩家 UUID 取能力，能力还不存在时用服务端下发的模型指派现建一个，
     * 并绑到替身实体上。
     *
     * <p>替身覆盖的远处玩家在客户端没有实体，能力通常要到「靠近过一次」之后才存在；服务端
     * 其实早就把模型指派发过来了（{@link PlayerModelSpecStore}），只是原先没有落脚点。
     * 这里让替身一出现就能把模型建出来，不必先贴脸加载。</p>
     */
    public static Optional<PlayerCapability> getForFarPlayer(Player proxy, UUID sourceUuid) {
        if (sourceUuid == null) {
            return Optional.empty();
        }
        PlayerCapability existing = STORE.get(sourceUuid);
        if (existing != null) {
            return Optional.of(existing);
        }
        PlayerModelSpecStore.Spec spec = PlayerModelSpecStore.get(sourceUuid);
        if (proxy == null || spec == null || !spec.isUsable()) {
            return Optional.empty();
        }
        PlayerCapability fresh = new PlayerCapability(proxy);
        fresh.initModelWithTexture(spec.modelId(), spec.textureId());
        fresh.setForceDisabled(spec.disabled());
        STORE.put(sourceUuid, fresh);
        return Optional.of(fresh);
    }

    public static void clear() {
        STORE.clear();
    }
}
