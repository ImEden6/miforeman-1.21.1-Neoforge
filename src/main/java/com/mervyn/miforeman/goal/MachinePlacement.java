package com.mervyn.miforeman.goal;

import com.mervyn.miforeman.network.LiveMonitoringPayload;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Graph nodes a linked machine can be placed on by hand, when the graph has no node for it. */
public final class MachinePlacement {
    private MachinePlacement() {
    }

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

    /** Only MACHINE nodes of the machine's own recipe type. A machine whose type the server couldn't
     *  read gets none, so it can't be placed where it can never run. */
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
