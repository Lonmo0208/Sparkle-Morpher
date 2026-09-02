package com.micaftic.exspm_hserverysm_model.server;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server configuration for the upload mod.
 *
 * <p>Values are read on the server thread whenever an upload session starts or
 * finishes, so a config reload takes effect immediately for the next request.</p>
 */
public final class YsmUploadConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ALLOW_UPLOAD = BUILDER
            .comment("Master switch for receiving model uploads. When false the mod accepts the channel but rejects every start request.")
            .define("allowUpload", true);

    private static final ModConfigSpec.BooleanValue REQUIRE_OP = BUILDER
            .comment("Only operators (permission level >= 2) may upload models. When false, " +
                    "non-operator players with a valid OP grant (see REQUIRE_GRANT) may upload.")
            .define("requireOp", false);

    private static final ModConfigSpec.BooleanValue REQUIRE_GRANT = BUILDER
            .comment("Non-operator players may only upload while holding a valid grant issued by an " +
                    "operator via /exspm_upload grant. Grants expire after grantDurationHours and " +
                    "must be re-granted afterwards. This blocks R18 uploads without operator review.")
            .define("requireGrant", true);

    private static final ModConfigSpec.IntValue GRANT_DURATION_HOURS = BUILDER
            .comment("How long an OP grant stays valid, in hours (default 12h). After expiry the " +
                    "player must ask an operator for a new grant.")
            .defineInRange("grantDurationHours", 12, 1, 24 * 365);

    private static final ModConfigSpec.IntValue MAX_MODEL_BYTES = BUILDER
            .comment("Maximum accepted model file size in bytes.")
            .defineInRange("maxModelBytes", 64 * 1024 * 1024, 1024, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue CHUNK_SIZE = BUILDER
            .comment("Suggested chunk size in bytes sent back to the client in the start acknowledgement.")
            .defineInRange("chunkSize", 32_000, 1024, 1_048_576);

    private static final ModConfigSpec.IntValue MAX_ZIP_ENTRIES = BUILDER
            .comment("Maximum number of entries accepted inside an uploaded .zip model pack.")
            .defineInRange("maxZipEntries", 4096, 1, 65536);

    private static final ModConfigSpec.IntValue MAX_ZIP_UNCOMPRESSED_BYTES = BUILDER
            .comment("Maximum total uncompressed size accepted for an uploaded .zip model pack.")
            .defineInRange("maxZipUncompressedBytes", 512 * 1024 * 1024, 1024, Integer.MAX_VALUE);

    private static final ModConfigSpec SPEC = BUILDER.build();

    private YsmUploadConfig() {
    }

    public static ModConfigSpec buildSpec() {
        return SPEC;
    }

    public static boolean allowUpload() {
        return ALLOW_UPLOAD.get();
    }

    public static boolean requireOp() {
        return REQUIRE_OP.get();
    }

    public static boolean requireGrant() {
        return REQUIRE_GRANT.get();
    }

    public static int grantDurationHours() {
        return GRANT_DURATION_HOURS.get();
    }

    public static int maxModelBytes() {
        return MAX_MODEL_BYTES.get();
    }

    public static int chunkSize() {
        return CHUNK_SIZE.get();
    }

    public static int maxZipEntries() {
        return MAX_ZIP_ENTRIES.get();
    }

    public static int maxZipUncompressedBytes() {
        return MAX_ZIP_UNCOMPRESSED_BYTES.get();
    }
}
