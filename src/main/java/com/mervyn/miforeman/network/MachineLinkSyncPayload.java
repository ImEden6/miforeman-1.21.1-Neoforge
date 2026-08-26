package com.mervyn.miforeman.network;

import com.mervyn.miforeman.MIForeman;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-client payload syncing a machine link state update to the client.
 */
public record MachineLinkSyncPayload(GlobalPos pos, boolean linked) implements CustomPacketPayload {
    public static final Type<MachineLinkSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "machine_link_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MachineLinkSyncPayload> STREAM_CODEC = StreamCodec.composite(
            GlobalPos.STREAM_CODEC, MachineLinkSyncPayload::pos,
            ByteBufCodecs.BOOL, MachineLinkSyncPayload::linked,
            MachineLinkSyncPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
