package com.micaftic.morpher.client.event;

import com.micaftic.morpher.YesSteveModel;
import com.micaftic.morpher.client.animation.AnimationRegister;
import com.micaftic.morpher.client.input.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.opengl.*;
import com.micaftic.morpher.core.api.PlatformAPI;

@EventBusSubscriber(modid = YesSteveModel.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientSetupEvent {
    private ClientSetupEvent() {}
    @SubscribeEvent public static void onSetup(FMLClientSetupEvent event) { if (YesSteveModel.isAvailable()) { AnimationRegister.registerAnimationState(); deregisterImageStreamJpegSpi(); } }
    @SubscribeEvent public static void onKeys(RegisterKeyMappingsEvent event) { registerKeyMappings(event); }
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(PlayerModelToggleKey.KEY_MAPPING);
        event.register(AnimationRouletteKey.KEY_ROULETTE); event.register(AnimationRouletteKey.KEY_LOCK);
        event.register(DebugAnimationKey.KEY_MAPPING);
        for (KeyMapping m : ExtraAnimationKey.getKeyMappings()) event.register(m);
    }
    public static Object nativeClientInit() {
        try {
            int max = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            if (max <= 0) return Component.literal("YSM: OpenGL context not available");
            try { int s = GL20.glCreateShader(GL20.GL_VERTEX_SHADER); if (s != 0) GL20.glDeleteShader(s); } catch (Exception e) { return Component.literal("YSM: GL20 (shaders) not available"); }
            return null;
        } catch (Exception e) { return Component.literal("sparkle Client Init Failed: " + e.getMessage()); }
    }

    /**
     * ImageStream 通过 ImageIO SPI（META-INF/services）注册自定义 JPEG 渲染器。在
     * NeoForge jarJar 环境下该 SPI 可能被优先选中，用它解码照片/贴图 JPG 会产生严重
     * 失真（实测 1357x1920 照片中心像素 232->141，最大偏差 558/765，画面中段变暗花屏）。
     * 这里只注销 JPEG 相关 SPI（保留 webp/avif 给 YSM 纹理解码用），强制 JPG 走 JDK 原生。
     */
    private static void deregisterImageStreamJpegSpi() {
        try {
            javax.imageio.ImageIO.scanForPlugins();
            javax.imageio.spi.IIORegistry registry = javax.imageio.spi.IIORegistry.getDefaultInstance();
            java.util.List<javax.imageio.spi.IIOServiceProvider> removeJpeg = new java.util.ArrayList<>();
            // 注意：getServiceProviders(Class, Filter, boolean) 的 Filter 不能为 null，用两参重载自筛。
            java.util.Iterator<javax.imageio.spi.ImageReaderSpi> readers = registry.getServiceProviders(
                    javax.imageio.spi.ImageReaderSpi.class, true);
            while (readers.hasNext()) {
                javax.imageio.spi.ImageReaderSpi spi = readers.next();
                if (spi.getClass().getName().startsWith("rip.ysm.imagestream.jpeg.")) {
                    removeJpeg.add(spi);
                }
            }
            java.util.Iterator<javax.imageio.spi.ImageWriterSpi> writers = registry.getServiceProviders(
                    javax.imageio.spi.ImageWriterSpi.class, true);
            while (writers.hasNext()) {
                javax.imageio.spi.ImageWriterSpi spi = writers.next();
                if (spi.getClass().getName().startsWith("rip.ysm.imagestream.jpeg.")) {
                    removeJpeg.add(spi);
                }
            }
            for (javax.imageio.spi.IIOServiceProvider spi : removeJpeg) {
                registry.deregisterServiceProvider(spi);
            }
        } catch (Throwable ignored) {
            // 注销失败不应影响游戏启动
        }
    }
}
