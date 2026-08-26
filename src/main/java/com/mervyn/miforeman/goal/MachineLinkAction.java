package com.mervyn.miforeman.goal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** A single undoable/redoable link/unlink toggle in the machine auto-detect review list. */
public record MachineLinkAction(GlobalPos pos, boolean from, boolean to) {
    public static final Codec<MachineLinkAction> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            GlobalPos.CODEC.fieldOf("pos").forGetter(MachineLinkAction::pos),
            Codec.BOOL.fieldOf("from").forGetter(MachineLinkAction::from),
            Codec.BOOL.fieldOf("to").forGetter(MachineLinkAction::to)
    ).apply(instance, MachineLinkAction::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, MachineLinkAction> STREAM_CODEC = StreamCodec.composite(
            GlobalPos.STREAM_CODEC, MachineLinkAction::pos,
            ByteBufCodecs.BOOL, MachineLinkAction::from,
            ByteBufCodecs.BOOL, MachineLinkAction::to,
            MachineLinkAction::new
    );
}
