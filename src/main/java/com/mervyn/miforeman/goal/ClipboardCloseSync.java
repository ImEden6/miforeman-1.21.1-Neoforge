package com.mervyn.miforeman.goal;

import java.util.Optional;
import org.jetbrains.annotations.Nullable;

public final class ClipboardCloseSync {
    private ClipboardCloseSync() {
    }

    /** Returns empty if {@code base} is null or neither field changed. */
    public static Optional<ProductionGoal> computeCloseSyncGoal(
            @Nullable ProductionGoal base, ClipboardUiState uiSnapshot, GraphLayoutState layoutSnapshot) {
        if (base == null) return Optional.empty();

        boolean layoutChanged = !layoutSnapshot.equals(base.graphLayout());
        if (uiSnapshot.equals(base.uiState()) && !layoutChanged) return Optional.empty();

        return Optional.of(base.withUiState(uiSnapshot).withGraphLayout(layoutSnapshot));
    }
}
