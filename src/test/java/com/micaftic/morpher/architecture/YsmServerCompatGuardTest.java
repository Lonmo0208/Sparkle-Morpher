package com.micaftic.morpher.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DragonVer 分支的存在意义 = 兼容「服务端是 YSM」的模型下发：客户端从 YSM 服务端
 * 拉取模型文件（通道 1/2），服务端把模型下发给客户端。
 *
 * <p>上游已转向纯云端分发，并在 4433b60e "remove server-side legacy model sync" 中
 * 删除整条服务端同步链路；2026-10-02 的合并曾因此静默丢掉该能力（恢复提交 012ab3a8）。
 * 本测试把兼容面钉住：后续合并若再删这里会红掉——要放弃就必须显式改这个测试，
 * 而不是在冲突解决里无声丢失。</p>
 */
class YsmServerCompatGuardTest {

    @Test
    void legacyModelSyncChannelsStayRegistered() throws IOException {
        Path protocol = locateRepository().resolve(
                "common/src/main/java/com/micaftic/morpher/network/protocol/LegacyModelProtocol.java");
        assertTrue(Files.isRegularFile(protocol),
                "LegacyModelProtocol 缺失：YSM 服务端模型同步通道（1/2/24）未注册");
        String source = Files.readString(protocol, StandardCharsets.UTF_8);
        assertTrue(source.contains("YSMChannel.register(1,"), "通道 1（S2C 模型文件同步）缺失");
        assertTrue(source.contains("YSMChannel.register(2,"), "通道 2（C2S 模型同步）缺失");
        assertTrue(source.contains("YSMChannel.register(24,"), "通道 24（女仆换模）缺失");
    }

    @Test
    void registrationStaysWiredInNetworkHandler() throws IOException {
        String source = Files.readString(locateRepository().resolve(
                "common/src/main/java/com/micaftic/morpher/network/NetworkHandler.java"), StandardCharsets.UTF_8);
        assertTrue(source.contains("LegacyCompatNetwork.register();"),
                "NetworkHandler.init() 不再注册 legacy 模型同步通道");
    }

    @Test
    void serverSideModelServingStaysPresent() throws IOException {
        Path repo = locateRepository();
        assertTrue(Files.isRegularFile(repo.resolve(
                        "src/neoforge/java/com/micaftic/morpher/model/LegacyModelSyncProtocol.java")),
                "服务端下发引擎 LegacyModelSyncProtocol 缺失");
        String manager = Files.readString(repo.resolve(
                "src/neoforge/java/com/micaftic/morpher/model/ServerModelManager.java"), StandardCharsets.UTF_8);
        assertTrue(manager.contains("nativeSyncModels("), "ServerModelManager 下发入口 nativeSyncModels 缺失");
        assertTrue(manager.contains("nativeSendModelData("), "ServerModelManager 下发入口 nativeSendModelData 缺失");
    }

    @Test
    void clientSideLegacySyncStaysPresent() throws IOException {
        Path repo = locateRepository();
        assertTrue(Files.isRegularFile(repo.resolve(
                        "common/src/main/java/com/micaftic/morpher/client/LegacyModelSyncClient.java")),
                "客户端 legacy 同步状态机缺失");
        assertTrue(Files.isRegularFile(repo.resolve(
                        "common/src/main/java/com/micaftic/morpher/client/LegacyModelCacheClient.java")),
                "客户端模型缓存/解密缺失");
    }

    private static Path locateRepository() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve(".github/workflows/ci.yml"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Could not locate repository root");
        return current;
    }
}
