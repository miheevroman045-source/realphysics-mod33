package com.example.homelander;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

/** One byte packet; the server validates it and all game rules. */
public record AbilityPayload(byte ability) implements CustomPayload {
    public static final Id<AbilityPayload> ID = new Id<>(HomelanderMod.id("ability"));
    public static final PacketCodec<RegistryByteBuf, AbilityPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.BYTE, AbilityPayload::ability, AbilityPayload::new);

    @Override
    public Id<? extends CustomPayload> getId() { return ID; }
}
