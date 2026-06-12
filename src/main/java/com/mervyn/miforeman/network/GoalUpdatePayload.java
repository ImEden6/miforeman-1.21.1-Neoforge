package com.mervyn.miforeman.network;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.goal.ProductionGoal;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.resources.ResourceLocation;

public record GoalUpdatePayload(ProductionGoal goal) implements CustomPacketPayload {
    public static final Type<GoalUpdatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "goal_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GoalUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            ProductionGoal.STREAM_CODEC,
            GoalUpdatePayload::goal,
            GoalUpdatePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void sendToServer() {
        PacketDistributor.sendToServer(this);
    }
}
