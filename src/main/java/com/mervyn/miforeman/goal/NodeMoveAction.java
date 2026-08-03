package com.mervyn.miforeman.goal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/** A single undoable/redoable node drag on GraphCanvas. */
public record NodeMoveAction(ResourceLocation nodeId, NodePosition from, NodePosition to) {
    public static final Codec<NodeMoveAction> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("node").forGetter(NodeMoveAction::nodeId),
            NodePosition.CODEC.fieldOf("from").forGetter(NodeMoveAction::from),
            NodePosition.CODEC.fieldOf("to").forGetter(NodeMoveAction::to)
    ).apply(instance, NodeMoveAction::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, NodeMoveAction> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, NodeMoveAction::nodeId,
            NodePosition.STREAM_CODEC, NodeMoveAction::from,
            NodePosition.STREAM_CODEC, NodeMoveAction::to,
            NodeMoveAction::new
    );
}
