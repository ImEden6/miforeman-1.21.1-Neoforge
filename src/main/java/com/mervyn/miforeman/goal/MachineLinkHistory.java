package com.mervyn.miforeman.goal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted undo/redo history for the machine auto-detect review list's link/unlink toggles.
 * Lives on {@link ProductionGoal} so it survives save/load and syncs client<->server the same
 * way as the rest of the goal (whole-object sync via GoalUpdatePayload, not delta).
 *
 * Unlike {@link GraphLayoutState}, this history does not duplicate current state: the single
 * source of truth for which machines are linked stays {@link ProductionGoal#linkedMachines()}.
 * This is purely an action log; the caller applies {@link UndoResult#linked()} to that list.
 *
 * Deliberately a separate, bespoke record from GraphLayoutState rather than a shared generic
 * history type: this codebase's codecs are all bespoke per-record (see ProductionGoal, FactoryPlan,
 * etc.), and a generic capped-history wrapper would need its own Codec/StreamCodec plumbing per
 * element type anyway, buying no real reuse for just two call sites.
 */
public record MachineLinkHistory(
        List<MachineLinkAction> undoStack,
        List<MachineLinkAction> redoStack
) {
    public static final int MAX_HISTORY = 20;
    public static final MachineLinkHistory EMPTY = new MachineLinkHistory(List.of(), List.of());

    /** Records a link/unlink toggle, capping the undo stack and clearing redo. */
    public MachineLinkHistory withToggle(BlockPos pos, boolean from, boolean to) {
        List<MachineLinkAction> undo = new ArrayList<>(undoStack);
        undo.add(new MachineLinkAction(pos, from, to));
        while (undo.size() > MAX_HISTORY) {
            undo.remove(0);
        }
        return new MachineLinkHistory(undo, List.of());
    }

    /** The updated history plus which machine changed link state and what it changed to. */
    public record UndoResult(MachineLinkHistory history, BlockPos pos, boolean linked) {}

    /**
     * Reverts the most recent toggle. The restored link state is unconditional — it does not
     * matter whether the machine is currently within scan range or still recipe-matches; linked
     * status is independent of live detection (mirrors how linkedMachines already behaves
     * elsewhere: a linked machine stays linked/monitored regardless of proximity).
     */
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
