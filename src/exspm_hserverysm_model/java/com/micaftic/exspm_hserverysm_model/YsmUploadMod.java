package com.micaftic.exspm_hserverysm_model;

import com.micaftic.exspm_hserverysm_model.network.YsmUploadPayload;
import com.micaftic.exspm_hserverysm_model.server.YsmUploadConfig;
import com.micaftic.exspm_hserverysm_model.server.YsmUploadServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-only upload mod entry point.
 *
 * <p>Runs on the dedicated server together with the official YSM mod. It opens
 * the independent {@code exspm_hserverysm_model:1} channel, receives model files uploaded by
 * the Sparkle's Morpher client, stores them into the official YSM custom model
 * folder ({@code config/yes_steve_model/custom}) and best-effort triggers a YSM
 * model reload so the new model becomes available to all players immediately.</p>
 *
 * <p>The whole module is dist-guarded: on a physical client this mod simply does
 * nothing (it is meant to be installed on servers only).</p>
 */
@Mod("exspm_hserverysm_model")
public final class YsmUploadMod {

    public static final String MOD_ID = "exspm_hserverysm_model";

    public static final Logger LOGGER = LoggerFactory.getLogger("exspm_hserverysm_model");

    public YsmUploadMod(IEventBus modEventBus, ModContainer modContainer) {
        // Server-only: do nothing on a physical client so the mod never
        // registers the upload channel there.
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) {
            return;
        }
        modContainer.registerConfig(ModConfig.Type.SERVER, YsmUploadConfig.buildSpec());
        modEventBus.addListener(YsmUploadMod::onRegisterPayloadHandlers);
    }

    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        YsmUploadPayload.initType();
        PayloadRegistrar registrar = event.registrar(YsmUploadPayload.VERSION).optional();
        registrar.playBidirectional(YsmUploadPayload.TYPE, YsmUploadPayload.CODEC, YsmUploadServer::handle);
    }
}
