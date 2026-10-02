package com.micaftic.morpher.network.message;

import com.micaftic.morpher.core.api.network.PacketContext;
import com.micaftic.morpher.model.ServerModelManager;
import com.micaftic.morpher.network.ClientNetworkBridge;
import com.micaftic.morpher.network.NetworkHandler;
import net.minecraft.network.FriendlyByteBuf;

public class S2CVersionCheckPacket {
    private final String version;
    private final boolean oysmServer;
    private final boolean allowUpload;
    /** 服务器品牌为 {@code open_ysm:v1}（SPM/OpenYSM 服务器）；官方 YSM 服务器无品牌，为 false。 */
    private final boolean spmServer;

    public S2CVersionCheckPacket() {
        this(NetworkHandler.VERSION, true, ServerModelManager.isModelUploadAllowed(), true);
    }

    private S2CVersionCheckPacket(String version, boolean oysmServer, boolean allowUpload, boolean spmServer) {
        this.version = version;
        this.oysmServer = oysmServer;
        this.allowUpload = allowUpload;
        this.spmServer = spmServer;
    }

    public static S2CVersionCheckPacket decode(FriendlyByteBuf buf) {
        String version = buf.readUtf();
        // 协议版本匹配（官方 YSM 2.6.x 也用 "2.6.0"）只代表"对方是 YSM 兼容服务器"，
        // 不代表支持 SPM 的专有判别号（女仆换模 24/假人 25-27/上传 70-74）。只有带
        // open_ysm:v1 品牌标记的才是 SPM/OpenYSM 服务器，才会额外声明 allowUpload；
        // 官方 YSM 服务器无此品牌、且解码不了这些判别号——发过去会触发解码崩溃断开连接
        // （上传包已踩过 Invalid index 的坑）。spmServer 即"可以安全发送 SPM 专有包"。
        boolean oysmServer = NetworkHandler.VERSION.equals(version);
        boolean allowUpload = false;
        boolean spmServer = false;
        if (buf.readableBytes() > 0) {
            String brand = buf.readUtf();
            if ("open_ysm:v1".equals(brand) && buf.readableBytes() > 0) {
                oysmServer = true;
                spmServer = true;
                allowUpload = buf.readBoolean();
            }
        }
        return new S2CVersionCheckPacket(version, oysmServer, allowUpload, spmServer);
    }

    public static void encode(S2CVersionCheckPacket message, FriendlyByteBuf buf) {
        buf.writeUtf(message.version);
        buf.writeUtf("open_ysm:v1");
        buf.writeBoolean(message.allowUpload);
    }

    public static void handle(S2CVersionCheckPacket message, PacketContext ctx) {
        ClientNetworkBridge.handle(ctx, "handleVersionCheck", message, ctx.getConnection());
    }

    public String getVersion() {
        return this.version;
    }

    public boolean isOysmServer() {
        return this.oysmServer;
    }

    public boolean isAllowUpload() {
        return this.allowUpload;
    }

    /** 服务器确认是 SPM/OpenYSM（品牌 {@code open_ysm:v1}）。SPM 专有判别号只允许发给它。 */
    public boolean isSpmServer() {
        return this.spmServer;
    }
}
