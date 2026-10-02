package com.micaftic.morpher.legacy.compat;

/**
 * Temporary 1.3.x compatibility boundary for the legacy handshake state.
 *
 * <p>New core code must depend on this facade instead of the legacy state
 * implementation. The complete legacy compatibility structure can be removed
 * behind this boundary in 1.4.0.</p>
 */
public final class LegacyCompatState {
    private static volatile boolean clientComplete;
    private static volatile boolean oysmServer;
    private static volatile boolean allowUpload;
    private static volatile boolean spmServer;

    private LegacyCompatState() {
    }

    public static void markClientComplete() {
        clientComplete = true;
    }

    public static void resetClientComplete() {
        clientComplete = false;
    }

    public static boolean isClientComplete() {
        return clientComplete;
    }

    public static boolean isClientSessionActive(boolean channelNegotiated) {
        return clientComplete || channelNegotiated;
    }

    public static void resetClientSession() {
        clientComplete = false;
        oysmServer = false;
        allowUpload = false;
        spmServer = false;
    }

    public static boolean isOysmServer() {
        return oysmServer;
    }

    public static void setOysmServer(boolean value) {
        oysmServer = value;
    }

    public static boolean isAllowUpload() {
        return allowUpload;
    }

    public static void setAllowUpload(boolean value) {
        allowUpload = value;
    }

    /** 服务器品牌为 open_ysm:v1（SPM/OpenYSM 服务器），可以安全接收 SPM 专有判别号。 */
    public static boolean isSpmServer() {
        return spmServer;
    }

    public static void setSpmServer(boolean value) {
        spmServer = value;
    }
}
