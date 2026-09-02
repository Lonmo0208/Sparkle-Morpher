package com.micaftic.exspm_hserverysm_model.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.micaftic.exspm_hserverysm_model.YsmUploadMod;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent store of OP-granted upload permissions.
 *
 * <p>To block R18 uploads, non-operator players may only upload a model while
 * they hold a valid grant issued by an operator. A grant expires after a
 * configurable window (default 12 hours); after expiry the player must ask an
 * operator to grant them again.</p>
 *
 * <p>Grants are persisted to {@code config/exspm_hserverysm_model/grants.json}
 * so they survive server restarts. Expired entries are pruned lazily on load,
 * on command use and during the upload permission probe.</p>
 */
public final class YsmUploadGrantStore {

    private static final String FILE_NAME = "grants.json";

    /** Sentinel for a permanent grant that never expires. */
    public static final long EXPIRY_NEVER = Long.MAX_VALUE;

    private static final Map<UUID, Long> GRANTS = new ConcurrentHashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private YsmUploadGrantStore() {
    }

    /** Loads persisted grants from disk. Call once at server start. */
    public static void load() {
        Path file = grantFile();
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) {
                return;
            }
            JsonArray entries = root.getAsJsonArray("grants");
            if (entries == null) {
                return;
            }
            GRANTS.clear();
            long now = System.currentTimeMillis();
            for (JsonElement element : entries) {
                JsonObject entry = element.getAsJsonObject();
                try {
                    UUID uuid = UUID.fromString(entry.get("uuid").getAsString());
                    long expiresAt = entry.get("expiresAt").getAsLong();
                    if (expiresAt == EXPIRY_NEVER || expiresAt > now) {
                        GRANTS.put(uuid, expiresAt);
                    }
                } catch (RuntimeException ignored) {
                    // skip malformed entries
                }
            }
            YsmUploadMod.LOGGER.info("Loaded {} upload grant(s) from {}", GRANTS.size(), file);
        } catch (IOException | RuntimeException e) {
            YsmUploadMod.LOGGER.warn("Failed to load upload grants from {}", file, e);
        }
    }

    /**
     * Issues or extends a grant for a player.
     *
     * @param durationMillis how long the grant stays valid from now.
     */
    public static void grant(UUID playerUuid, long durationMillis) {
        GRANTS.put(playerUuid, System.currentTimeMillis() + durationMillis);
        save();
    }

    /** Issues a permanent grant that never expires, for trusted players. */
    public static void grantPermanent(UUID playerUuid) {
        GRANTS.put(playerUuid, EXPIRY_NEVER);
        save();
    }

    /** Revokes any grant held by the player. */
    public static void revoke(UUID playerUuid) {
        GRANTS.remove(playerUuid);
        save();
    }

    /** Returns the expiry timestamp the player's grant, or 0 when none/expired. */
    public static long expiryMillis(UUID playerUuid) {
        Long expiresAt = GRANTS.get(playerUuid);
        if (expiresAt == null) {
            return 0L;
        }
        if (expiresAt == EXPIRY_NEVER) {
            return EXPIRY_NEVER;
        }
        if (expiresAt <= System.currentTimeMillis()) {
            GRANTS.remove(playerUuid);
            return 0L;
        }
        return expiresAt;
    }

    /** True when the player currently holds a valid (unexpired) grant. */
    public static boolean isGranted(UUID playerUuid) {
        return expiryMillis(playerUuid) > 0L;
    }

    /** True when the player's grant is permanent. */
    public static boolean isPermanent(UUID playerUuid) {
        return expiryMillis(playerUuid) == EXPIRY_NEVER;
    }

    /** Snapshot of every currently valid grant, for the list command. */
    public static List<Entry> snapshot() {
        long now = System.currentTimeMillis();
        List<Entry> result = new ArrayList<>();
        GRANTS.forEach((uuid, expiresAt) -> {
            if (expiresAt == EXPIRY_NEVER || expiresAt > now) {
                result.add(new Entry(uuid, expiresAt));
            }
        });
        result.sort((a, b) -> Long.compare(a.expiresAt(), b.expiresAt()));
        return result;
    }

    /** Removes expired grants and persists when anything was dropped (permanent grants stay). */
    public static void prune() {
        long now = System.currentTimeMillis();
        boolean changed = GRANTS.entrySet().removeIf(
                entry -> entry.getValue() != EXPIRY_NEVER && entry.getValue() <= now);
        if (changed) {
            save();
        }
    }

    private static void save() {
        Path file = grantFile();
        try {
            Files.createDirectories(file.getParent());
            JsonArray entries = new JsonArray();
            long now = System.currentTimeMillis();
            GRANTS.forEach((uuid, expiresAt) -> {
                if (expiresAt == EXPIRY_NEVER || expiresAt > now) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("uuid", uuid.toString());
                    entry.addProperty("expiresAt", expiresAt);
                    entries.add(entry);
                }
            });
            JsonObject root = new JsonObject();
            root.add("grants", entries);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            YsmUploadMod.LOGGER.warn("Failed to save upload grants to {}", file, e);
        }
    }

    private static Path grantFile() {
        return FMLPaths.CONFIGDIR.get().resolve("exspm_hserverysm_model").resolve(FILE_NAME);
    }

    /** One valid grant entry. */
    public record Entry(UUID playerUuid, long expiresAt) {
        public boolean permanent() {
            return expiresAt == EXPIRY_NEVER;
        }
    }
}