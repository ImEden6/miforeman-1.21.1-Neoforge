package com.mervyn.miforeman.goal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * Persisted UI state for {@code ClipboardScreen}, including active tab, camera position, and view toggles.
 * Saved on {@link ProductionGoal} to preserve UI state across screen reopens.
 */
public record ClipboardUiState(
        int lastStep,
        double cameraX,
        double cameraY,
        float cameraZoom,
        GraphViewMode graphViewMode,
        boolean graphDragEnabled,
        boolean detailCardCollapsed,
        boolean isMinimized,
        boolean showMachineNumbers
) {
    public enum GraphViewMode implements StringRepresentable {
        ALL("all"),
        ITEMS_ONLY("items_only"),
        MACHINES_ONLY("machines_only");

        private final String name;

        GraphViewMode(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        public static final Codec<GraphViewMode> CODEC = StringRepresentable.fromEnum(GraphViewMode::values);
        public static final StreamCodec<RegistryFriendlyByteBuf, GraphViewMode> STREAM_CODEC =
                ByteBufCodecs.fromCodec(CODEC).cast();

        public GraphViewMode next() {
            return switch (this) {
                case ALL -> ITEMS_ONLY;
                case ITEMS_ONLY -> MACHINES_ONLY;
                case MACHINES_ONLY -> ALL;
            };
        }
    }

    public static final ClipboardUiState EMPTY =
            new ClipboardUiState(2, 0.0, 0.0, 1.0f, GraphViewMode.ALL, true, false, false, true);

    public ClipboardUiState(int lastStep, double cameraX, double cameraY, float cameraZoom,
                            boolean showMachineNodes, boolean graphDragEnabled, boolean detailCardCollapsed, boolean isMinimized) {
        this(lastStep, cameraX, cameraY, cameraZoom,
                showMachineNodes ? GraphViewMode.ALL : GraphViewMode.ITEMS_ONLY,
                graphDragEnabled, detailCardCollapsed, isMinimized, true);
    }

    public boolean showMachineNodes() {
        return graphViewMode == GraphViewMode.ALL;
    }

    public static final Codec<ClipboardUiState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("last_step").forGetter(ClipboardUiState::lastStep),
            Codec.DOUBLE.fieldOf("camera_x").forGetter(ClipboardUiState::cameraX),
            Codec.DOUBLE.fieldOf("camera_y").forGetter(ClipboardUiState::cameraY),
            Codec.FLOAT.fieldOf("camera_zoom").forGetter(ClipboardUiState::cameraZoom),
            GraphViewMode.CODEC.optionalFieldOf("graph_view_mode", GraphViewMode.ALL).forGetter(ClipboardUiState::graphViewMode),
            Codec.BOOL.fieldOf("graph_drag_enabled").forGetter(ClipboardUiState::graphDragEnabled),
            Codec.BOOL.fieldOf("detail_card_collapsed").forGetter(ClipboardUiState::detailCardCollapsed),
            Codec.BOOL.fieldOf("minimized").forGetter(ClipboardUiState::isMinimized),
            Codec.BOOL.optionalFieldOf("show_machine_numbers", true).forGetter(ClipboardUiState::showMachineNumbers)
    ).apply(instance, ClipboardUiState::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClipboardUiState> STREAM_CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, ClipboardUiState>() {
        @Override
        public ClipboardUiState decode(RegistryFriendlyByteBuf buf) {
            int lastStep = ByteBufCodecs.INT.decode(buf);
            double cameraX = ByteBufCodecs.DOUBLE.decode(buf);
            double cameraY = ByteBufCodecs.DOUBLE.decode(buf);
            float cameraZoom = ByteBufCodecs.fromCodec(Codec.FLOAT).decode(buf);
            GraphViewMode graphViewMode = GraphViewMode.STREAM_CODEC.decode(buf);
            boolean graphDragEnabled = ByteBufCodecs.BOOL.decode(buf);
            boolean detailCardCollapsed = ByteBufCodecs.BOOL.decode(buf);
            boolean isMinimized = ByteBufCodecs.BOOL.decode(buf);
            boolean showMachineNumbers = ByteBufCodecs.BOOL.decode(buf);
            return new ClipboardUiState(lastStep, cameraX, cameraY, cameraZoom,
                    graphViewMode, graphDragEnabled, detailCardCollapsed, isMinimized, showMachineNumbers);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ClipboardUiState state) {
            ByteBufCodecs.INT.encode(buf, state.lastStep());
            ByteBufCodecs.DOUBLE.encode(buf, state.cameraX());
            ByteBufCodecs.DOUBLE.encode(buf, state.cameraY());
            ByteBufCodecs.fromCodec(Codec.FLOAT).encode(buf, state.cameraZoom());
            GraphViewMode.STREAM_CODEC.encode(buf, state.graphViewMode());
            ByteBufCodecs.BOOL.encode(buf, state.graphDragEnabled());
            ByteBufCodecs.BOOL.encode(buf, state.detailCardCollapsed());
            ByteBufCodecs.BOOL.encode(buf, state.isMinimized());
            ByteBufCodecs.BOOL.encode(buf, state.showMachineNumbers());
        }
    };
}
