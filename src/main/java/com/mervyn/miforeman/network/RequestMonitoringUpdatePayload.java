package com.mervyn.miforeman.network;

import com.mervyn.miforeman.MIForeman;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;

/** hand tells the server which clipboard to report on when the player holds one in each hand. */
public record RequestMonitoringUpdatePayload(InteractionHand hand) implements CustomPacketPayload {
    public static final Type<RequestMonitoringUpdatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "request_monitoring_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestMonitoringUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL.map(
                    offHand -> offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                    hand -> hand == InteractionHand.OFF_HAND),
            RequestMonitoringUpdatePayload::hand,
            RequestMonitoringUpdatePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void sendToServer() {
        PacketDistributor.sendToServer(this);
    }
}
