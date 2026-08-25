package com.mervyn.miforeman.goal;

import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * Computes goal updates to persist when closing the clipboard UI.
 */
public final class ClipboardCloseSync {
    private ClipboardCloseSync() {
    }

    /**
     * Returns {@code base} updated with UI state and graph layout snapshots.
     * Returns empty if {@code base} is null or if neither field changed.
     */
    public static Optional<ProductionGoal> computeCloseSyncGoal(
            @Nullable ProductionGoal base, ClipboardUiState uiSnapshot, GraphLayoutState layoutSnapshot) {
        if (base == null) return Optional.empty(); // nothing saved yet, nothing to attach ui state to

        boolean layoutChanged = !layoutSnapshot.equals(base.graphLayout());
        if (uiSnapshot.equals(base.uiState()) && !layoutChanged) return Optional.empty();

        return Optional.of(base.withUiState(uiSnapshot).withGraphLayout(layoutSnapshot));
    }
}
