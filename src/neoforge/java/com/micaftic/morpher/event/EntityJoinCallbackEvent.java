package com.micaftic.morpher.event;

import com.micaftic.morpher.YesSteveModel;
import com.micaftic.morpher.capability.ModelInfoCapability;
import com.micaftic.morpher.capability.PlayerCapability;
import com.google.common.cache.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import com.micaftic.morpher.core.api.PlatformAPI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class EntityJoinCallbackEvent {
    private static final Cache<Integer, List<Consumer<Entity>>> cache = CacheBuilder.newBuilder().expireAfterAccess(30, TimeUnit.SECONDS).build();

    
    private static final Map<Integer, String> LAST_SEEN_MODEL_ID = new ConcurrentHashMap<>();
    
    private static final Map<Integer, Boolean> LAST_SEEN_DISABLED = new ConcurrentHashMap<>();

    private static int tickCounter;
    private static final int POLL_INTERVAL_TICKS = 5; 

    private EntityJoinCallbackEvent() {}

    public static void register() {
        if (PlatformAPI.isServer()) return;
        NeoForge.EVENT_BUS.addListener(EntityJoinCallbackEvent::onJoin);
        NeoForge.EVENT_BUS.addListener(EntityJoinCallbackEvent::onLeave);
    }

    // ====================================================================================
    //  Event handlers
    // ====================================================================================

    private static void onJoin(EntityJoinLevelEvent event) {
        Entity e = event.getEntity();
        if (!YesSteveModel.isAvailable() || !event.getLevel().isClientSide()) return;

        
        List<Consumer<Entity>> list = cache.getIfPresent(e.getId());
        if (list != null) for (Consumer<Entity> c : list) c.accept(e);
        cache.invalidate(e.getId());

        
        if (e instanceof Player player) {
            tryInitFromAttachment(player);
        }
    }

    private static void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) return;
        int entityId = event.getEntity().getId();
        LAST_SEEN_MODEL_ID.remove(entityId);
        LAST_SEEN_DISABLED.remove(entityId);
    }

    // ====================================================================================
    //  Tick polling — called from ClientTickEvent
    // ====================================================================================

    
    public static void tickPoll() {
        tickCounter++;
        if (tickCounter % POLL_INTERVAL_TICKS != 0) return;

        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null) return;

        for (Player player : level.players()) {
            tryInitFromAttachment(player);
        }
    }

    // ====================================================================================
    
    // ====================================================================================

    
    private static boolean tryInitFromAttachment(Player player) {
        int entityId = player.getId();

        ModelInfoCapability attach;
        try {
            attach = ModelInfoCapability.get(player).orElse(null);
        } catch (Throwable t) {
            
            LAST_SEEN_MODEL_ID.remove(entityId);
            LAST_SEEN_DISABLED.remove(entityId);
            return false;
        }

        if (attach == null) {
            LAST_SEEN_MODEL_ID.remove(entityId);
            LAST_SEEN_DISABLED.remove(entityId);
            return false;
        }

        String modelId = attach.getModelId();
        if (modelId == null || modelId.isBlank() || "default".equals(modelId)) {
            
            LAST_SEEN_MODEL_ID.remove(entityId);
            LAST_SEEN_DISABLED.remove(entityId);
            return false;
        }

        String textureId = attach.getSelectTexture();
        final String finalTextureId = (textureId == null || textureId.isBlank()) ? "" : textureId;
        boolean disabled = attach.isDisabled();

        
        String lastModelId = LAST_SEEN_MODEL_ID.get(entityId);
        Boolean lastDisabled = LAST_SEEN_DISABLED.get(entityId);
        boolean modelChanged = !modelId.equals(lastModelId);
        boolean disabledChanged = lastDisabled == null || lastDisabled != disabled;

        if (!modelChanged && !disabledChanged) {
            return true; 
        }

        LAST_SEEN_MODEL_ID.put(entityId, modelId);
        LAST_SEEN_DISABLED.put(entityId, disabled);

        PlayerCapability.get(player).ifPresent(cap -> {
            cap.initModelWithTexture(modelId, finalTextureId);
            cap.setForceDisabled(disabled);
        });

        return true;
    }

    // ====================================================================================
    //  Public helpers
    // ====================================================================================

    
    public static void invalidate(int entityId) {
        LAST_SEEN_MODEL_ID.remove(entityId);
        LAST_SEEN_DISABLED.remove(entityId);
    }

    public static void addCallback(int id, Consumer<Entity> consumer) {
        Minecraft.getInstance().execute(() -> {
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) {
                Entity entity = level.getEntity(id);
                if (entity != null) { consumer.accept(entity); } else { List<Consumer<Entity>> l = cache.getIfPresent(id); if (l == null) { l = new ArrayList<>(3); cache.put(id, l); } l.add(consumer); }
            }
        });
    }
}
