package com.mervyn.miforeman.goal;

import java.util.Optional;
import org.jetbrains.annotations.Nullable;

/**
 * Decides what {@code ClipboardScreen.removed()} should persist on close, if anything. This used
 * to be a method on {@code ClipboardScreen} itself, but that class extends the client-only
 * {@code Screen}, and NeoForge's RuntimeDistCleaner refuses to load it on a dedicated server, even
 * just to call an unrelated static method. Moving the logic here, with no client dependency, lets
 * {@code ForemanGameTests} call it directly.
 */
public final class ClipboardCloseSync {
    private ClipboardCloseSync() {
    }

    /**
     * Returns {@code base}, the last goal synced to the server, with the UI-state and graph-layout
     * fields replaced and nothing else touched. That keeps draft edits to name/target/rate/plan
     * elsewhere in the screen from leaking through on a plain close. Returns empty if {@code base}
     * is null (nothing saved yet) or if neither field changed.
     */
    public static Optional<ProductionGoal> computeCloseSyncGoal(
            @Nullable ProductionGoal base, ClipboardUiState uiSnapshot, GraphLayoutState layoutSnapshot) {
        if (base == null) return Optional.empty(); // nothing saved yet, nothing to attach ui state to

        boolean layoutChanged = !layoutSnapshot.equals(base.graphLayout());
        if (uiSnapshot.equals(base.uiState()) && !layoutChanged) return Optional.empty();

        return Optional.of(base.withUiState(uiSnapshot).withGraphLayout(layoutSnapshot));
    }
}
