package com.micaftic.morpher.capability.client;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按玩家 UUID 记住模型指派，并**落盘到本地**。
 *
 * <p>远处玩家（被 VSS/voxy 替身覆盖）在客户端没有实体，模型信息原本只能靠实体加入时落到玩家身上，
 * 所以「必须贴脸加载过一次」远处才有模型。这里把已经加载过的模型按 UUID 记下来存到
 * {@code config/sparkle_morpher/far_player_models.txt}，之后（包括下次进游戏）替身一出现就能直接
 * 用本地记录把模型建出来，不需要服务端配合、也不需要再贴脸。</p>
 */
public final class PlayerModelSpecStore {

    private static final ConcurrentMap<UUID, Spec> SPECS = new ConcurrentHashMap<>();

    private static final String FILE_NAME = "far_player_models.txt";

    private static volatile boolean loaded;

    private PlayerModelSpecStore() {
    }

    /** 服务端下发的模型指派。 */
    public record Spec(String modelId, String textureId, boolean disabled) {

        public boolean isUsable() {
            return this.modelId != null && !this.modelId.isBlank() && !"default".equals(this.modelId);
        }
    }

    public static void put(UUID uuid, String modelId, String textureId, boolean disabled) {
        if (uuid == null) {
            return;
        }
        Spec spec = new Spec(modelId, textureId == null ? "" : textureId, disabled);
        if (!spec.isUsable()) {
            // default 表示该玩家没有自定义模型（客户端本来就走原版皮肤），不留记录。
            SPECS.remove(uuid);
            save();
            return;
        }
        ensureLoaded();
        SPECS.put(uuid, spec);
        save();
    }

    @Nullable
    public static Spec get(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        ensureLoaded();
        return SPECS.get(uuid);
    }

    public static void remove(UUID uuid) {
        if (uuid != null && SPECS.remove(uuid) != null) {
            save();
        }
    }

    public static void clear() {
        SPECS.clear();
        save();
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = file();
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length < 2) {
                    continue;
                }
                try {
                    Spec spec = new Spec(parts[1], parts.length > 2 ? parts[2] : "", parts.length > 3 && Boolean.parseBoolean(parts[3]));
                    if (spec.isUsable()) {
                        SPECS.put(UUID.fromString(parts[0]), spec);
                    }
                } catch (IllegalArgumentException ignored) {
                    // 单行损坏就跳过，不影响其它记录。
                }
            }
        } catch (IOException ignored) {
            // 读取失败按空缓存处理，下次写入会重建。
        }
    }

    private static void save() {
        Path path = file();
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            List<String> lines = SPECS.entrySet().stream()
                    .map(entry -> entry.getKey() + "\t" + entry.getValue().modelId() + "\t" + entry.getValue().textureId() + "\t" + entry.getValue().disabled())
                    .toList();
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 落盘失败只影响下次进游戏的兜底，本次游戏内的记录仍然有效。
        }
    }

    @Nullable
    private static Path file() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameDirectory == null) {
            return null;
        }
        return minecraft.gameDirectory.toPath().resolve("config").resolve("sparkle_morpher").resolve(FILE_NAME);
    }
}
