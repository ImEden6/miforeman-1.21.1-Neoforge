package com.mervyn.miforeman.network;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.goal.ProductionGoal;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.resources.ResourceLocation;

/** {@code hand} identifies which hand's clipboard this update is for. Without it, the
 *  server cannot tell which clipboard to write to when a player holds one in each hand. */
public record GoalUpdatePayload(ProductionGoal goal, InteractionHand hand) implements CustomPacketPayload {
    public static final Type<GoalUpdatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MIForeman.MODID, "goal_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GoalUpdatePayload> STREAM_CODEC = StreamCodec.composite(
            ProductionGoal.STREAM_CODEC,
            GoalUpdatePayload::goal,
            ByteBufCodecs.BOOL.map(
                    offHand -> offHand ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND,
                    hand -> hand == InteractionHand.OFF_HAND),
            GoalUpdatePayload::hand,
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
