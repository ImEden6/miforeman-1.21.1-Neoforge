package com.mervyn.miforeman.goal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Undo and redo history for machine link toggles.
 * Saved on {@link ProductionGoal} and synchronized between client and server.
 */
public record MachineLinkHistory(
        List<MachineLinkAction> undoStack,
        List<MachineLinkAction> redoStack
) {
    public static final int MAX_HISTORY = 20;
    public static final MachineLinkHistory EMPTY = new MachineLinkHistory(List.of(), List.of());

    /** Records a link/unlink toggle, capping the undo stack and clearing redo. */
    public MachineLinkHistory withToggle(GlobalPos pos, boolean from, boolean to) {
        List<MachineLinkAction> undo = new ArrayList<>(undoStack);
        undo.add(new MachineLinkAction(pos, from, to));
        while (undo.size() > MAX_HISTORY) {
            undo.remove(0);
        }
        return new MachineLinkHistory(undo, List.of());
    }

    /** The updated history plus which machine changed link state and what it changed to. */
    public record UndoResult(MachineLinkHistory history, GlobalPos pos, boolean linked) {}

    /** Reverts the most recent toggle. */
    public @Nullable UndoResult undo() {
        if (undoStack.isEmpty()) {
            return null;
        }
        List<MachineLinkAction> undo = new ArrayList<>(undoStack);
        MachineLinkAction action = undo.remove(undo.size() - 1);

        List<MachineLinkAction> redo = new ArrayList<>(redoStack);
        redo.add(action);

        return new UndoResult(new MachineLinkHistory(undo, redo), action.pos(), action.from());
    }

    /** Re-applies the most recently undone toggle. */
    public @Nullable UndoResult redo() {
        if (redoStack.isEmpty()) {
            return null;
        }
        List<MachineLinkAction> redo = new ArrayList<>(redoStack);
        MachineLinkAction action = redo.remove(redo.size() - 1);

        List<MachineLinkAction> undo = new ArrayList<>(undoStack);
        undo.add(action);

        return new UndoResult(new MachineLinkHistory(undo, redo), action.pos(), action.to());
    }

    public static final Codec<MachineLinkHistory> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            MachineLinkAction.CODEC.listOf().fieldOf("undo_stack").forGetter(MachineLinkHistory::undoStack),
            MachineLinkAction.CODEC.listOf().fieldOf("redo_stack").forGetter(MachineLinkHistory::redoStack)
    ).apply(instance, MachineLinkHistory::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, MachineLinkHistory> STREAM_CODEC = StreamCodec.composite(
            MachineLinkAction.STREAM_CODEC.apply(ByteBufCodecs.list()),
            MachineLinkHistory::undoStack,
            MachineLinkAction.STREAM_CODEC.apply(ByteBufCodecs.list()),
            MachineLinkHistory::redoStack,
            MachineLinkHistory::new
    );
}
