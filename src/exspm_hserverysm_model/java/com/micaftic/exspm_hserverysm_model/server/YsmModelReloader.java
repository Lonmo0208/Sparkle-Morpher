package com.micaftic.exspm_hserverysm_model.server;

import com.micaftic.exspm_hserverysm_model.YsmUploadMod;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Best-effort trigger for the official YSM model reload after a file has been
 * written into the custom folder.
 *
 * <p>Primary path: reflectively call {@code ServerModelManager.loadModels(null,
 * null)} (official YSM 1.20.1 package). It rescans {@code built/}, {@code
 * custom/} and {@code auth/} on a background thread, publishes the new model
 * definitions and syncs every connected player's model data. Passing {@code
 * null} callbacks is safe (the parameters are {@code @Nullable}).</p>
 *
 * <p>Fallback path: dispatch the in-game command {@code /ysm model reload} with
 * a permission-4 command source. This also works when the internal class
 * signature changes between YSM versions.</p>
 */
public final class YsmModelReloader {

    private static final String YSM_SERVER_MODEL_MANAGER = "com.elfmcys.yesstevemodel.model.ServerModelManager";

    private YsmModelReloader() {
    }

    public static boolean requestReload(MinecraftServer server) {
        if (server == null) {
            return false;
        }
        if (reloadViaReflection()) {
            return true;
        }
        return reloadViaCommand(server);
    }

    private static boolean reloadViaReflection() {
        try {
            Class<?> clazz = Class.forName(YSM_SERVER_MODEL_MANAGER);
            Method loadModels = clazz.getMethod("loadModels", Consumer.class, Consumer.class);
            Object result = loadModels.invoke(null, null, null);
            if (result instanceof Boolean started && started) {
                YsmUploadMod.LOGGER.info("YSM model reload triggered via ServerModelManager.loadModels");
                return true;
            }
            return false;
        } catch (Throwable t) {
            YsmUploadMod.LOGGER.debug("Reflective YSM reload unavailable, falling back to command", t);
            return false;
        }
    }

    private static boolean reloadViaCommand(MinecraftServer server) {
        try {
            String command = "/ysm model reload";
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withPermission(4), command);
            YsmUploadMod.LOGGER.info("YSM model reload command '{}' dispatched", command);
            return true;
        } catch (Throwable t) {
            YsmUploadMod.LOGGER.warn("Failed to dispatch YSM model reload command", t);
            return false;
        }
    }
}
