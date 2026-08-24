package com.mervyn.miforeman.goal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ProductionGoal(
        String name,
        TargetType type,
        ResourceLocation targetId,
        double rate,
        Map<ResourceLocation, ResourceLocation> recipeSelections,
        Optional<FactoryPlan> plan,
        boolean perHour,
        double threshold,
        List<BlockPos> linkedMachines,
        GraphLayoutState graphLayout,
        MachineLinkHistory machineLinkHistory,
        List<BlockPos> rejectedMachines,
        ClipboardUiState uiState
) {
    public ProductionGoal(String name, TargetType type, ResourceLocation targetId, double rate) {
        this(name, type, targetId, rate, Map.of(), Optional.empty(), false, 0.8, List.of(), GraphLayoutState.EMPTY, MachineLinkHistory.EMPTY, List.of());
    }

    public ProductionGoal(String name, TargetType type, ResourceLocation targetId, double rate, Map<ResourceLocation, ResourceLocation> recipeSelections, Optional<FactoryPlan> plan) {
        this(name, type, targetId, rate, recipeSelections, plan, false, 0.8, List.of(), GraphLayoutState.EMPTY, MachineLinkHistory.EMPTY, List.of());
    }

    public ProductionGoal(String name, TargetType type, ResourceLocation targetId, double rate, Map<ResourceLocation, ResourceLocation> recipeSelections, Optional<FactoryPlan> plan, boolean perHour, double threshold, List<BlockPos> linkedMachines) {
        this(name, type, targetId, rate, recipeSelections, plan, perHour, threshold, linkedMachines, GraphLayoutState.EMPTY, MachineLinkHistory.EMPTY, List.of());
    }

    public ProductionGoal(String name, TargetType type, ResourceLocation targetId, double rate, Map<ResourceLocation, ResourceLocation> recipeSelections, Optional<FactoryPlan> plan, boolean perHour, double threshold, List<BlockPos> linkedMachines, GraphLayoutState graphLayout) {
        this(name, type, targetId, rate, recipeSelections, plan, perHour, threshold, linkedMachines, graphLayout, MachineLinkHistory.EMPTY, List.of());
    }

    public ProductionGoal(String name, TargetType type, ResourceLocation targetId, double rate, Map<ResourceLocation, ResourceLocation> recipeSelections, Optional<FactoryPlan> plan, boolean perHour, double threshold, List<BlockPos> linkedMachines, GraphLayoutState graphLayout, MachineLinkHistory machineLinkHistory) {
        this(name, type, targetId, rate, recipeSelections, plan, perHour, threshold, linkedMachines, graphLayout, machineLinkHistory, List.of());
    }

    public ProductionGoal(String name, TargetType type, ResourceLocation targetId, double rate, Map<ResourceLocation, ResourceLocation> recipeSelections, Optional<FactoryPlan> plan, boolean perHour, double threshold, List<BlockPos> linkedMachines, GraphLayoutState graphLayout, MachineLinkHistory machineLinkHistory, List<BlockPos> rejectedMachines) {
        this(name, type, targetId, rate, recipeSelections, plan, perHour, threshold, linkedMachines, graphLayout, machineLinkHistory, rejectedMachines, ClipboardUiState.EMPTY);
    }

    public ProductionGoal withGraphLayout(GraphLayoutState graphLayout) {
        return new ProductionGoal(name, type, targetId, rate, recipeSelections, plan, perHour, threshold, linkedMachines, graphLayout, machineLinkHistory, rejectedMachines, uiState);
    }

    public ProductionGoal withPlan(Optional<FactoryPlan> plan) {
        return new ProductionGoal(name, type, targetId, rate, recipeSelections, plan, perHour, threshold, linkedMachines, graphLayout, machineLinkHistory, rejectedMachines, uiState);
    }

    public ProductionGoal withRecipeSelections(Map<ResourceLocation, ResourceLocation> recipeSelections) {
        return new ProductionGoal(name, type, targetId, rate, recipeSelections, plan, perHour, threshold, linkedMachines, graphLayout, machineLinkHistory, rejectedMachines, uiState);
    }

    public ProductionGoal withLinkedMachines(List<BlockPos> linkedMachines, MachineLinkHistory machineLinkHistory) {
        return new ProductionGoal(name, type, targetId, rate, recipeSelections, plan, perHour, threshold, linkedMachines, graphLayout, machineLinkHistory, rejectedMachines, uiState);
    }

    public ProductionGoal withUiState(ClipboardUiState uiState) {
        return new ProductionGoal(name, type, targetId, rate, recipeSelections, plan, perHour, threshold, linkedMachines, graphLayout, machineLinkHistory, rejectedMachines, uiState);
    }

    /** Adds a machine to the rejected set (no-op if already present — rejectedMachines is used as a set despite the List representation, matching linkedMachines' existing convention). */
    public ProductionGoal withRejectedMachine(BlockPos pos) {
        if (rejectedMachines.contains(pos)) {
            return this;
        }
        List<BlockPos> updated = new java.util.ArrayList<>(rejectedMachines);
        updated.add(pos);
        return new ProductionGoal(name, type, targetId, rate, recipeSelections, plan, perHour, threshold, linkedMachines, graphLayout, machineLinkHistory, updated, uiState);
    }

    /** Removes a machine from the rejected set (un-reject). No-op if not present. */
    public ProductionGoal withoutRejectedMachine(BlockPos pos) {
        if (!rejectedMachines.contains(pos)) {
            return this;
        }
        List<BlockPos> updated = new java.util.ArrayList<>(rejectedMachines);
        updated.remove(pos);
        return new ProductionGoal(name, type, targetId, rate, recipeSelections, plan, perHour, threshold, linkedMachines, graphLayout, machineLinkHistory, updated, uiState);
    }

    public enum TargetType implements StringRepresentable {
        ITEM("item"),
        FLUID("fluid");

        private final String name;

        TargetType(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    public record MachineRequirement(
            ResourceLocation machineId,
            double count
    ) {
        public static final Codec<MachineRequirement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("machine").forGetter(MachineRequirement::machineId),
                Codec.DOUBLE.fieldOf("count").forGetter(MachineRequirement::count)
        ).apply(instance, MachineRequirement::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, MachineRequirement> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC,
                MachineRequirement::machineId,
                ByteBufCodecs.DOUBLE,
                MachineRequirement::count,
                MachineRequirement::new
        );
    }

    public record MaterialFlow(
            TargetType type,
            ResourceLocation resourceId,
            double rate
    ) {
        public static final Codec<MaterialFlow> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                StringRepresentable.fromEnum(TargetType::values).fieldOf("type").forGetter(MaterialFlow::type),
                ResourceLocation.CODEC.fieldOf("resource").forGetter(MaterialFlow::resourceId),
                Codec.DOUBLE.fieldOf("rate").forGetter(MaterialFlow::rate)
        ).apply(instance, MaterialFlow::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, MaterialFlow> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.fromCodec(StringRepresentable.fromEnum(TargetType::values)),
                MaterialFlow::type,
                ResourceLocation.STREAM_CODEC,
                MaterialFlow::resourceId,
                ByteBufCodecs.DOUBLE,
                MaterialFlow::rate,
                MaterialFlow::new
        );
    }

    public record Ambiguity(
            ResourceLocation resourceId,
            List<ResourceLocation> recipeIds
    ) {
        public static final Codec<Ambiguity> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceLocation.CODEC.fieldOf("resource").forGetter(Ambiguity::resourceId),
                ResourceLocation.CODEC.listOf().fieldOf("recipes").forGetter(Ambiguity::recipeIds)
        ).apply(instance, Ambiguity::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, Ambiguity> STREAM_CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC,
                Ambiguity::resourceId,
                ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()),
                Ambiguity::recipeIds,
                Ambiguity::new
        );
    }

    public record FactoryPlan(
            List<MachineRequirement> machines,
            List<MaterialFlow> rawInputs,
            List<MaterialFlow> intermediateFlows,
            List<Ambiguity> ambiguities,
            @org.jetbrains.annotations.Nullable RecipeGraph graph
    ) {
        public FactoryPlan(List<MachineRequirement> machines, List<MaterialFlow> rawInputs, List<MaterialFlow> intermediateFlows, List<Ambiguity> ambiguities) {
            this(machines, rawInputs, intermediateFlows, ambiguities, null);
        }

        public FactoryPlan withGraph(RecipeGraph graph) {
            return new FactoryPlan(this.machines, this.rawInputs, this.intermediateFlows, this.ambiguities, graph);
        }

        public static final Codec<FactoryPlan> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                MachineRequirement.CODEC.listOf().fieldOf("machines").forGetter(FactoryPlan::machines),
                MaterialFlow.CODEC.listOf().fieldOf("raw_inputs").forGetter(FactoryPlan::rawInputs),
                MaterialFlow.CODEC.listOf().fieldOf("intermediates").forGetter(FactoryPlan::intermediateFlows),
                Ambiguity.CODEC.listOf().fieldOf("ambiguities").forGetter(FactoryPlan::ambiguities)
        ).apply(instance, FactoryPlan::new));

        public static final StreamCodec<RegistryFriendlyByteBuf, FactoryPlan> STREAM_CODEC = StreamCodec.composite(
                MachineRequirement.STREAM_CODEC.apply(ByteBufCodecs.list()),
                FactoryPlan::machines,
                MaterialFlow.STREAM_CODEC.apply(ByteBufCodecs.list()),
                FactoryPlan::rawInputs,
                MaterialFlow.STREAM_CODEC.apply(ByteBufCodecs.list()),
                FactoryPlan::intermediateFlows,
                Ambiguity.STREAM_CODEC.apply(ByteBufCodecs.list()),
                FactoryPlan::ambiguities,
                FactoryPlan::new
        );
    }

    public static final Codec<ProductionGoal> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(ProductionGoal::name),
            StringRepresentable.fromEnum(TargetType::values).fieldOf("type").forGetter(ProductionGoal::type),
            ResourceLocation.CODEC.fieldOf("target").forGetter(ProductionGoal::targetId),
            Codec.DOUBLE.fieldOf("rate").forGetter(ProductionGoal::rate),
            Codec.unboundedMap(ResourceLocation.CODEC, ResourceLocation.CODEC).fieldOf("selections").forGetter(ProductionGoal::recipeSelections),
            FactoryPlan.CODEC.optionalFieldOf("plan").forGetter(ProductionGoal::plan),
            Codec.BOOL.optionalFieldOf("per_hour", false).forGetter(ProductionGoal::perHour),
            Codec.DOUBLE.optionalFieldOf("threshold", 0.8).forGetter(ProductionGoal::threshold),
            BlockPos.CODEC.listOf().optionalFieldOf("linked_machines", List.of()).forGetter(ProductionGoal::linkedMachines),
            GraphLayoutState.CODEC.optionalFieldOf("graph_layout", GraphLayoutState.EMPTY).forGetter(ProductionGoal::graphLayout),
            MachineLinkHistory.CODEC.optionalFieldOf("machine_link_history", MachineLinkHistory.EMPTY).forGetter(ProductionGoal::machineLinkHistory),
            BlockPos.CODEC.listOf().optionalFieldOf("rejected_machines", List.of()).forGetter(ProductionGoal::rejectedMachines),
            ClipboardUiState.CODEC.optionalFieldOf("ui_state", ClipboardUiState.EMPTY).forGetter(ProductionGoal::uiState)
    ).apply(instance, ProductionGoal::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProductionGoal> STREAM_CODEC = new StreamCodec<RegistryFriendlyByteBuf, ProductionGoal>() {
        @Override
        public ProductionGoal decode(RegistryFriendlyByteBuf buf) {
            String name = ByteBufCodecs.STRING_UTF8.decode(buf);
            TargetType type = ByteBufCodecs.fromCodec(StringRepresentable.fromEnum(TargetType::values)).decode(buf);
            ResourceLocation targetId = ResourceLocation.STREAM_CODEC.decode(buf);
            double rate = ByteBufCodecs.DOUBLE.decode(buf);
            Map<ResourceLocation, ResourceLocation> recipeSelections = ByteBufCodecs.map(java.util.HashMap::new, ResourceLocation.STREAM_CODEC, ResourceLocation.STREAM_CODEC).decode(buf);
            Optional<FactoryPlan> plan = ByteBufCodecs.optional(FactoryPlan.STREAM_CODEC).decode(buf);
            boolean perHour = ByteBufCodecs.BOOL.decode(buf);
            double threshold = ByteBufCodecs.DOUBLE.decode(buf);
            List<BlockPos> linkedMachines = ByteBufCodecs.fromCodec(BlockPos.CODEC).apply(ByteBufCodecs.list()).decode(buf);
            GraphLayoutState graphLayout = GraphLayoutState.STREAM_CODEC.decode(buf);
            MachineLinkHistory machineLinkHistory = MachineLinkHistory.STREAM_CODEC.decode(buf);
            List<BlockPos> rejectedMachines = ByteBufCodecs.fromCodec(BlockPos.CODEC).apply(ByteBufCodecs.list()).decode(buf);
            ClipboardUiState uiState = ClipboardUiState.STREAM_CODEC.decode(buf);
            return new ProductionGoal(name, type, targetId, rate, recipeSelections, plan, perHour, threshold, linkedMachines, graphLayout, machineLinkHistory, rejectedMachines, uiState);
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ProductionGoal goal) {
            ByteBufCodecs.STRING_UTF8.encode(buf, goal.name());
            ByteBufCodecs.fromCodec(StringRepresentable.fromEnum(TargetType::values)).encode(buf, goal.type());
            ResourceLocation.STREAM_CODEC.encode(buf, goal.targetId());
            ByteBufCodecs.DOUBLE.encode(buf, goal.rate());
            ByteBufCodecs.map(java.util.HashMap::new, ResourceLocation.STREAM_CODEC, ResourceLocation.STREAM_CODEC).encode(buf, new java.util.HashMap<>(goal.recipeSelections()));
            ByteBufCodecs.optional(FactoryPlan.STREAM_CODEC).encode(buf, goal.plan());
            ByteBufCodecs.BOOL.encode(buf, goal.perHour());
            ByteBufCodecs.DOUBLE.encode(buf, goal.threshold());
            ByteBufCodecs.fromCodec(BlockPos.CODEC).apply(ByteBufCodecs.list()).encode(buf, goal.linkedMachines());
            GraphLayoutState.STREAM_CODEC.encode(buf, goal.graphLayout());
            MachineLinkHistory.STREAM_CODEC.encode(buf, goal.machineLinkHistory());
            ByteBufCodecs.fromCodec(BlockPos.CODEC).apply(ByteBufCodecs.list()).encode(buf, goal.rejectedMachines());
            ClipboardUiState.STREAM_CODEC.encode(buf, goal.uiState());
        }
    };
}

