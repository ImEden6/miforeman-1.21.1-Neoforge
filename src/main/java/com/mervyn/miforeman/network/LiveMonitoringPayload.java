package com.mervyn.miforeman.network;

import com.mervyn.miforeman.MIForeman;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

public record LiveMonitoringPayload(List<LiveMonitoringPayload.MachineStatusData> machines) implements CustomPacketPayload {
    public static final Type<LiveMonitoringPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "live_monitoring"));

    public record MachineStatusData(BlockPos pos, String status, double actualRate, ResourceLocation machineId,
                                     Optional<ResourceLocation> recipeId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, MachineStatusData> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, MachineStatusData::pos,
                ByteBufCodecs.STRING_UTF8, MachineStatusData::status,
                ByteBufCodecs.DOUBLE, MachineStatusData::actualRate,
                ResourceLocation.STREAM_CODEC, MachineStatusData::machineId,
                ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), MachineStatusData::recipeId,
                MachineStatusData::new
        );
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, LiveMonitoringPayload> STREAM_CODEC = StreamCodec.composite(
            MachineStatusData.STREAM_CODEC.apply(ByteBufCodecs.list()),
            LiveMonitoringPayload::machines,
            LiveMonitoringPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
