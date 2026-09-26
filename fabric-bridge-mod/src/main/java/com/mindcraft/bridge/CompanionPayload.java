package com.mindcraft.bridge;

import com.mindcraft.protocol.BridgeProtocol;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record CompanionPayload(String json) implements CustomPayload {
    public static final CustomPayload.Id<CompanionPayload> ID = new CustomPayload.Id<>(
        Identifier.of(BridgeProtocol.CHANNEL_NAMESPACE, BridgeProtocol.CHANNEL_PATH));

    public static final PacketCodec<PacketByteBuf, CompanionPayload> CODEC =
        PacketCodec.tuple(PacketCodecs.string(262144), CompanionPayload::json, CompanionPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
