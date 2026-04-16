package com.mcaiassistant.visionaddon;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MC AI Vision Addon - Fabric 客户端附属 mod
 *
 * 与 Paper 服务端通过 Plugin Messaging Channel 通信：
 * - C->S `mcai:vision/hello`：声明客户端已安装视觉附属 mod
 * - S->C `mcai:vision/request`：请求客户端截取当前游戏画面
 * - C->S `mcai:vision/response`：回传 base64 JPEG 截图
 */
public class McAiVisionMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("mcai-vision");

    @Override
    public void onInitializeClient() {
        VisionPackets.registerPayloadTypes();
        VisionPackets.registerConnectionEvents();
        VisionPackets.registerClientReceivers();
        LOGGER.info("[MC AI Vision] 客户端附属 mod 已加载");
    }
}
