package com.mervyn.miforeman.network;

import com.mervyn.miforeman.MIForeman;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

public record RequestMonitoringUpdatePayload() implements CustomPacketPayload {
    public static final Type<RequestMonitoringUpdatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "request_monitoring_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestMonitoringUpdatePayload> STREAM_CODEC = StreamCodec.unit(new RequestMonitoringUpdatePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void sendToServer() {
        PacketDistributor.sendToServer(this);
    }
}
