package com.mervyn.miforeman.network;

import com.mervyn.miforeman.MIForeman;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-to-client only. Sent after {@code ForemanClipboardItem.useOn()} links/unlinks a machine
 * by shift-right-clicking it in the world -- that path writes straight to the item's data
 * component server-side and has no other way to tell the client's WorldHighlightRenderer a
 * position's linked state changed.
 */
public record MachineLinkSyncPayload(BlockPos pos, boolean linked) implements CustomPacketPayload {
    public static final Type<MachineLinkSyncPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "machine_link_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MachineLinkSyncPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, MachineLinkSyncPayload::pos,
            ByteBufCodecs.BOOL, MachineLinkSyncPayload::linked,
            MachineLinkSyncPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
