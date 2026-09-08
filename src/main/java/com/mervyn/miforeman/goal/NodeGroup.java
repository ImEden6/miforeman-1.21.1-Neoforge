package com.mervyn.miforeman.goal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A locked cluster of GraphCanvas nodes with pure membership and no layout data.
 * {@link GraphLayoutEngine} places each locked group as a single block during auto-arrange.
 */
public record NodeGroup(UUID id, Set<ResourceLocation> memberIds) {
    public static final Codec<NodeGroup> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(NodeGroup::id),
            ResourceLocation.CODEC.listOf().<Set<ResourceLocation>>xmap(HashSet::new, ArrayList::new)
                    .fieldOf("members").forGetter(NodeGroup::memberIds)
    ).apply(instance, NodeGroup::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, NodeGroup> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, NodeGroup::id,
            ByteBufCodecs.collection(HashSet::new, ResourceLocation.STREAM_CODEC), NodeGroup::memberIds,
            NodeGroup::new
    );
}
