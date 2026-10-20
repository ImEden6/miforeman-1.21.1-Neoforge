package com.mervyn.miforeman.goal;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

public record HistoryEntry(List<NodeMoveAction> moves) {
    /** Reads the list format and the older single-move format, writes only the list format. */
    public static final Codec<HistoryEntry> CODEC = Codec.either(
            NodeMoveAction.CODEC.listOf(),
            NodeMoveAction.CODEC
    ).xmap(
            either -> either.map(HistoryEntry::new, single -> new HistoryEntry(List.of(single))),
            entry -> Either.left(entry.moves())
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, HistoryEntry> STREAM_CODEC = StreamCodec.composite(
            NodeMoveAction.STREAM_CODEC.apply(ByteBufCodecs.list()), HistoryEntry::moves,
            HistoryEntry::new
    );
}
