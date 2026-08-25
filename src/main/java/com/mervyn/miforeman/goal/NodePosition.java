package com.mervyn.miforeman.goal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Position coordinates of a node in canvas space.
 */
public record NodePosition(int x, int y) {
    public static final Codec<NodePosition> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("x").forGetter(NodePosition::x),
            Codec.INT.fieldOf("y").forGetter(NodePosition::y)
    ).apply(instance, NodePosition::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, NodePosition> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, NodePosition::x,
            ByteBufCodecs.INT, NodePosition::y,
            NodePosition::new
    );
}
