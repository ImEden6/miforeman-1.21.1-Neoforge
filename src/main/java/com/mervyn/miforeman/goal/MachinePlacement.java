package com.mervyn.miforeman.goal;

import com.mervyn.miforeman.network.LiveMonitoringPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which graph nodes a linked machine can be placed on by hand, for machines the graph otherwise
 * has no node for. See {@link ProductionGoal#withMachineAssignment}.
 */
public final class MachinePlacement {
    private MachinePlacement() {
    }

    /** How a linked machine relates to the graph, which decides what the placement list offers. */
    public enum Kind {
        UNPLACED,
        ASSIGNED,
        OFF_PLAN,
        ON_GRAPH
    }

    public static Kind classify(LiveMonitoringPayload.MachineStatusData machine, Set<ResourceLocation> machineNodeIds) {
        if (machine.assigned()) return Kind.ASSIGNED;
        Optional<ResourceLocation> recipe = machine.recipeId();
        if (recipe.isEmpty()) return Kind.UNPLACED;
        if (machineNodeIds.contains(recipe.get())) return Kind.ON_GRAPH;
        return machine.status() == MachineStatus.RED ? Kind.UNPLACED : Kind.OFF_PLAN;
    }

    /** MACHINE nodes whose recipe type is the machine's own, so a compressor is only ever offered
     *  compressor recipes. A machine whose type the server couldn't read gets none: offering every
     *  node would let it be placed somewhere it can never actually run. */
    public static List<RecipeGraphNode> compatibleNodes(Collection<RecipeGraphNode> nodes, @Nullable ResourceLocation recipeTypeId) {
        List<RecipeGraphNode> result = new ArrayList<>();
        if (recipeTypeId == null) return result;
        for (RecipeGraphNode node : nodes) {
            if (node.getType() == NodeType.MACHINE && recipeTypeId.equals(node.getMachineType())) {
                result.add(node);
            }
        }
        return result;
    }
}
