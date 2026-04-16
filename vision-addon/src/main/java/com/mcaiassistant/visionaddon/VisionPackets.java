package com.mcaiassistant.visionaddon;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public final class VisionPackets {

    public static final Identifier REQUEST_IDENTIFIER = Identifier.of("mcai", "vision/request");
    public static final Identifier RESPONSE_IDENTIFIER = Identifier.of("mcai", "vision/response");
    public static final Identifier HELLO_IDENTIFIER = Identifier.of("mcai", "vision/hello");
    public static final int MAX_RESPONSE_CHUNK_BYTES = 29_990;

    private VisionPackets() {
    }

    public record HelloPayload(String message) implements CustomPayload {
        public static final Id<HelloPayload> ID = new Id<>(HELLO_IDENTIFIER);
        public static final PacketCodec<RegistryByteBuf, HelloPayload> CODEC = PacketCodec.of(
                (HelloPayload payload, RegistryByteBuf buf) -> buf.writeString(payload.message()),
                buf -> new HelloPayload(buf.readString())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record RequestPayload() implements CustomPayload {
        public static final Id<RequestPayload> ID = new Id<>(REQUEST_IDENTIFIER);
        public static final PacketCodec<RegistryByteBuf, RequestPayload> CODEC = PacketCodec.unit(new RequestPayload());

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public record ResponsePayload(long requestId, int chunkIndex, int chunkCount, int crc32, String base64Chunk) implements CustomPayload {
        public static final Id<ResponsePayload> ID = new Id<>(RESPONSE_IDENTIFIER);
        public static final PacketCodec<RegistryByteBuf, ResponsePayload> CODEC = PacketCodec.of(
                (ResponsePayload payload, RegistryByteBuf buf) -> {
                    buf.writeVarLong(payload.requestId());
                    buf.writeVarInt(payload.chunkIndex());
                    buf.writeVarInt(payload.chunkCount());
                    buf.writeInt(payload.crc32());
                    buf.writeString(payload.base64Chunk(), MAX_RESPONSE_CHUNK_BYTES);
                },
                buf -> new ResponsePayload(
                        buf.readVarLong(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readInt(),
                        buf.readString(MAX_RESPONSE_CHUNK_BYTES)
                )
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    public static void registerPayloadTypes() {
        PayloadTypeRegistry.playS2C().register(RequestPayload.ID, RequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(HelloPayload.ID, HelloPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ResponsePayload.ID, ResponsePayload.CODEC);
    }

    public static void registerClientReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(RequestPayload.ID, (payload, context) -> {
            McAiVisionMod.LOGGER.info("[MC AI Vision] 收到服务端截图请求");
            context.client().execute(ScreenshotHandler::captureAndSend);
        });
    }

    public static void registerConnectionEvents() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ClientPlayNetworking.send(new HelloPayload("mcai-vision:1.0.0"));
            McAiVisionMod.LOGGER.info("[MC AI Vision] 已向服务端发送能力声明: {}", HELLO_IDENTIFIER);
        });
    }
}
