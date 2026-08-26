package com.mervyn.miforeman.network;

import com.mervyn.miforeman.MIForeman;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public record ScanResultPayload(List<ScanResultPayload.Candidate> candidates) implements CustomPacketPayload {
    public record Candidate(GlobalPos pos, ResourceLocation machineId, ResourceLocation recipeId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Candidate> STREAM_CODEC = StreamCodec.composite(
                GlobalPos.STREAM_CODEC, Candidate::pos,
                ResourceLocation.STREAM_CODEC, Candidate::machineId,
                ResourceLocation.STREAM_CODEC, Candidate::recipeId,
                Candidate::new
        );
    }

    public static final Type<ScanResultPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "scan_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScanResultPayload> STREAM_CODEC = StreamCodec.composite(
            Candidate.STREAM_CODEC.apply(ByteBufCodecs.list()),
            ScanResultPayload::candidates,
            ScanResultPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
