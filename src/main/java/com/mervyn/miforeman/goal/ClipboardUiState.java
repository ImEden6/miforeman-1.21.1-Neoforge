package com.mervyn.miforeman.goal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Which page of {@code ClipboardScreen} the player was last on, plus the graph camera and
 * view-toggle state -- persisted on {@link ProductionGoal} the same way {@link GraphLayoutState}
 * is, so closing and reopening the clipboard returns to where the player left off instead of
 * always landing back on the Monitor step.
 */
public record ClipboardUiState(
        int lastStep,
        double cameraX,
        double cameraY,
        float cameraZoom,
        boolean showMachineNodes,
        boolean graphDragEnabled,
        boolean detailCardCollapsed,
        boolean isMinimized
) {
    // lastStep=2 mirrors ClipboardScreen.STEP_MONITOR -- the pre-existing default for any goal
    // decoded without this field (legacy saves from before this record existed).
    public static final ClipboardUiState EMPTY =
            new ClipboardUiState(2, 0.0, 0.0, 1.0f, true, true, false, false);

    public static final Codec<ClipboardUiState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("last_step").forGetter(ClipboardUiState::lastStep),
            Codec.DOUBLE.fieldOf("camera_x").forGetter(ClipboardUiState::cameraX),
            Codec.DOUBLE.fieldOf("camera_y").forGetter(ClipboardUiState::cameraY),
            Codec.FLOAT.fieldOf("camera_zoom").forGetter(ClipboardUiState::cameraZoom),
            Codec.BOOL.fieldOf("show_machine_nodes").forGetter(ClipboardUiState::showMachineNodes),
            Codec.BOOL.fieldOf("graph_drag_enabled").forGetter(ClipboardUiState::graphDragEnabled),
            Codec.BOOL.fieldOf("detail_card_collapsed").forGetter(ClipboardUiState::detailCardCollapsed),
            Codec.BOOL.fieldOf("minimized").forGetter(ClipboardUiState::isMinimized)
    ).apply(instance, ClipboardUiState::new));

    // Hand-written like ProductionGoal.STREAM_CODEC rather than StreamCodec.composite(...) --
    // 8 fields is past composite()'s supported overload range.
    public static final StreamCodec<RegistryFriendlyByteBuf, ClipboardUiState> STREAM_CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, ClipboardUiState>() {
        @Override
        public ClipboardUiState decode(RegistryFriendlyByteBuf buf) {
            int lastStep = ByteBufCodecs.INT.decode(buf);
            double cameraX = ByteBufCodecs.DOUBLE.decode(buf);
            double cameraY = ByteBufCodecs.DOUBLE.decode(buf);
            float cameraZoom = ByteBufCodecs.fromCodec(Codec.FLOAT).decode(buf);
            boolean showMachineNodes = ByteBufCodecs.BOOL.decode(buf);
            boolean graphDragEnabled = ByteBufCodecs.BOOL.decode(buf);
            boolean detailCardCollapsed = ByteBufCodecs.BOOL.decode(buf);
            boolean isMinimized = ByteBufCodecs.BOOL.decode(buf);
            return new ClipboardUiState(lastStep, cameraX, cameraY, cameraZoom,
                    showMachineNodes, graphDragEnabled, detailCardCollapsed, isMinimized);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ClipboardUiState state) {
            ByteBufCodecs.INT.encode(buf, state.lastStep());
            ByteBufCodecs.DOUBLE.encode(buf, state.cameraX());
            ByteBufCodecs.DOUBLE.encode(buf, state.cameraY());
            ByteBufCodecs.fromCodec(Codec.FLOAT).encode(buf, state.cameraZoom());
            ByteBufCodecs.BOOL.encode(buf, state.showMachineNodes());
            ByteBufCodecs.BOOL.encode(buf, state.graphDragEnabled());
            ByteBufCodecs.BOOL.encode(buf, state.detailCardCollapsed());
            ByteBufCodecs.BOOL.encode(buf, state.isMinimized());
        }
    };
}
