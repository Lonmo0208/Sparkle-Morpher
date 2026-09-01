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

    public static void clear() {
        STORE.clear();
    }
}
