package com.mervyn.miforeman.network;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.goal.ProductionGoal;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sends the whole ProductionGoal (not just a radius) because the server needs
 * goal.plan().graph() to build the recipe index for matching -- same whole-object convention
 * GoalUpdatePayload already uses.
 */
public record ScanRequestPayload(ProductionGoal goal) implements CustomPacketPayload {
    public static final Type<ScanRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "scan_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScanRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ProductionGoal.STREAM_CODEC,
            ScanRequestPayload::goal,
            ScanRequestPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void sendToServer() {
        PacketDistributor.sendToServer(this);
    }
}
