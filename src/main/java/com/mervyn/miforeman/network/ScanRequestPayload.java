package com.mervyn.miforeman.network;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.goal.ProductionGoal;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-to-server payload containing the current {@link ProductionGoal} to execute a machine scan.
 * {@code radiusChunks} is the player's pick, or {@link com.mervyn.miforeman.goal.MachineScanner#DEFAULT_RADIUS}
 * for the server's configured default; the server clamps it either way.
 */
public record ScanRequestPayload(ProductionGoal goal, int radiusChunks) implements CustomPacketPayload {
    public static final Type<ScanRequestPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "scan_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScanRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ProductionGoal.STREAM_CODEC,
            ScanRequestPayload::goal,
            ByteBufCodecs.VAR_INT,
            ScanRequestPayload::radiusChunks,
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
