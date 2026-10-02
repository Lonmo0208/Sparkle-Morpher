package com.micaftic.morpher.client.compat.touhoulittlemaid;

import com.micaftic.morpher.core.compat.touhoulittlemaid.TouhouLittleMaidAccess;

import com.micaftic.morpher.client.ClientModelManager;
import com.micaftic.morpher.client.gui.ModernPlayerModelScreen;
import com.micaftic.morpher.network.NetworkHandler;
import com.micaftic.morpher.network.message.C2SSetMaidModelPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Soft-link integration with the official TartaricAcid Touhou Little Maid.
 *
 * <p>The official mod already exposes a YSM button and a serverbound model
 * packet. SparkleMorpher only needs to make the optional YSM hook visible,
 * open its model screen for the selected maid, and mirror the official synced
 * state into its renderer.</p>
 */
final class OfficialTouhouLittleMaidCompat {
    private static final String OPEN_SCREEN_EVENT =
            "com.github.tartaricacid.touhoulittlemaid.compat.ysm.event.OpenYsmMaidScreenEvent";
    private static final String MODEL_PACKET =
            "com.github.tartaricacid.touhoulittlemaid.network.message.YsmMaidModelPackage";

    private OfficialTouhouLittleMaidCompat() {
    }

    static void init(Logger logger) {
        if (!TouhouLittleMaidAccess.isLoaded()
                || ModList.get().isLoaded("yes_steve_model")
                || FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        try {
            Class<?> eventClass = Class.forName(OPEN_SCREEN_EVENT, false,
                    OfficialTouhouLittleMaidCompat.class.getClassLoader());
            registerOpenScreenListener(eventClass);
            logger.info("Enabled official Touhou Little Maid YSM model screen integration");
        } catch (Throwable throwable) {
            logger.debug("Official Touhou Little Maid YSM screen integration unavailable: {}",
                    throwable.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerOpenScreenListener(Class<?> eventClass) {
        registerOpenScreenListenerTyped((Class<? extends net.neoforged.bus.api.Event>) eventClass);
    }

    private static <T extends net.neoforged.bus.api.Event> void registerOpenScreenListenerTyped(Class<T> eventClass) {
        NeoForge.EVENT_BUS.addListener(eventClass, OfficialTouhouLittleMaidCompat::onOpenScreen);
    }

    private static void onOpenScreen(net.neoforged.bus.api.Event event) {
        try {
            Method getMaid = event.getClass().getMethod("getMaid");
            Object value = getMaid.invoke(event);
            if (!(value instanceof Entity maid) || !TouhouLittleMaidAccess.isMaid(maid)) {
                return;
            }
            Minecraft.getInstance().setScreen(new ModernPlayerModelScreen(
                    (modelId, texture) -> applyModel(maid, modelId, texture),
                    "maid:" + maid.getUUID()));
        } catch (Throwable ignored) {
            // The event is optional and must never affect the maid screen.
        }
    }

    private static void applyModel(Entity maid, String modelId, String texture) {
        if (modelId == null || modelId.isBlank()) {
            return;
        }
        String resolvedTexture = texture == null ? "" : texture;
        // 只有服务器确认是 SPM/OpenYSM（品牌 open_ysm:v1）才发 SPM 专有女仆包（判别号 24）：
        // 官方 YSM 服务器（协议同源但判别号不同）解不了这个包，会解码崩溃断连踢人
        // ——与 70-74 上传包踩过的 Invalid index 同一类问题。
        if (ClientModelManager.isSpmServer()) {
            try {
                NetworkHandler.sendToServer(new C2SSetMaidModelPacket(maid.getId(), modelId, resolvedTexture));
                return;
            } catch (Throwable ignored) {
                // SPM 包不可用：不再混发官方包，避免两套协议互相干扰
                return;
            }
        }
        // 非 YSM 兼容服务器（没回过版本握手）：服务端不存在 YSM 模型，发任何换模包都会因
        // 目标包未注册被踢，直接只做本地选择。
        if (!ClientModelManager.isOysmServer()) {
            return;
        }
        // 官方 YSM 服务器 + 车万女仆：走车万女仆官方协议 YsmMaidModelPackage（官方 YSM 自己
        // 引用该类做服务端换模），语义与官方客户端一致。
        try {
            Class<?> packetClass = Class.forName(MODEL_PACKET, false,
                    OfficialTouhouLittleMaidCompat.class.getClassLoader());
            Constructor<?> constructor = packetClass.getConstructor(
                    int.class, String.class, String.class, Component.class);
            Object packet = constructor.newInstance(
                    maid.getId(), modelId, resolvedTexture, Component.literal(modelId));
            if (packet instanceof CustomPacketPayload payload) {
                PacketDistributor.sendToServer(payload);
            }
        } catch (Throwable ignored) {
            // An unsupported TLM version must not affect the model screen.
        }
    }
}
