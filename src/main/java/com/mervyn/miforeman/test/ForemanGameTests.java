package com.mervyn.miforeman.test;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.RecipeGraphTraverser;
import com.mervyn.miforeman.registry.ModComponents;
import com.mervyn.miforeman.registry.ModItems;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import aztech.modern_industrialization.machines.MachineBlockEntity;
import com.mervyn.miforeman.goal.ServerMonitoringManager;
import com.mervyn.miforeman.goal.UnifiedCrafter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

@GameTestHolder(MIForeman.MODID)
@PrefixGameTestTemplate(false)
public class ForemanGameTests {

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testProductionGoalComponent(GameTestHelper helper) {
        String goalName = "test_goal";
        ProductionGoal.TargetType type = ProductionGoal.TargetType.ITEM;
        ResourceLocation targetId = ResourceLocation.parse("minecraft:iron_ingot");
        double rate = 10.5;

        ProductionGoal goal = new ProductionGoal(goalName, type, targetId, rate);
        ItemStack stack = new ItemStack(ModItems.FOREMAN_CLIPBOARD_ITEM.get());
        stack.set(ModComponents.PRODUCTION_GOAL.get(), goal);

        ProductionGoal retrievedGoal = stack.get(ModComponents.PRODUCTION_GOAL.get());
        if (retrievedGoal == null) {
            helper.fail("Production goal was not saved/retrieved from ItemStack components.");
            return;
        }

        if (!retrievedGoal.name().equals(goalName)) {
            helper.fail("Expected goal name: " + goalName + ", but got: " + retrievedGoal.name());
        }
        if (retrievedGoal.type() != type) {
            helper.fail("Expected type: " + type + ", but got: " + retrievedGoal.type());
        }
        if (!retrievedGoal.targetId().equals(targetId)) {
            helper.fail("Expected targetId: " + targetId + ", but got: " + retrievedGoal.targetId());
        }
        if (retrievedGoal.rate() != rate) {
            helper.fail("Expected rate: " + rate + ", but got: " + retrievedGoal.rate());
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testProductionGoalStreamCodecParity(GameTestHelper helper) {
        var level = helper.getLevel();

        ProductionGoal goal = new ProductionGoal(
                "parity_goal",
                ProductionGoal.TargetType.FLUID,
                ResourceLocation.parse("minecraft:water"),
                12.5,
                Map.of(ResourceLocation.parse("minecraft:stone"), ResourceLocation.parse("minecraft:cobblestone")),
                java.util.Optional.of(new ProductionGoal.FactoryPlan(
                        List.of(new ProductionGoal.MachineRequirement(
                                ResourceLocation.parse("modern_industrialization:assembler"), 3.0, 32L, 96L)),
                        List.of(new ProductionGoal.MaterialFlow(ProductionGoal.TargetType.ITEM,
                                ResourceLocation.parse("minecraft:iron_ingot"), 4.0)),
                        List.of(),
                        List.of(new ProductionGoal.Ambiguity(ResourceLocation.parse("minecraft:dye"),
                                List.of(ResourceLocation.parse("minecraft:red_dye")))))),
                true,
                0.6,
                List.of(GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, new BlockPos(1, 2, 3))),
                com.mervyn.miforeman.goal.GraphLayoutState.EMPTY,
                com.mervyn.miforeman.goal.MachineLinkHistory.EMPTY,
                List.of(GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, new BlockPos(4, 5, 6))),
                new com.mervyn.miforeman.goal.ClipboardUiState(1, 12.5, -3.0, 2.0f,
                        com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode.MACHINES_ONLY, false, true, true,
                        false, true));

        @SuppressWarnings("deprecation")
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                level.registryAccess());
        ProductionGoal.STREAM_CODEC.encode(buf, goal);
        ProductionGoal decoded = ProductionGoal.STREAM_CODEC.decode(buf);

        if (!decoded.equals(goal)) {
            helper.fail("STREAM_CODEC round-trip does not match original ProductionGoal. A field was likely "
                    + "added to CODEC without updating STREAM_CODEC (or vice versa). Original: " + goal + ", decoded: "
                    + decoded);
            return;
        }

        var legacyJson = new com.google.gson.JsonArray();
        var blockPosArray = new com.google.gson.JsonArray();
        blockPosArray.add(10);
        blockPosArray.add(20);
        blockPosArray.add(30);
        legacyJson.add(blockPosArray);

        var decodedLegacy = ProductionGoal.GLOBAL_POS_LIST_CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                legacyJson);
        if (decodedLegacy.isError()) {
            helper.fail("GLOBAL_POS_LIST_CODEC failed to parse legacy BlockPos list: "
                    + decodedLegacy.error().get().message());
            return;
        }
        var expectedLegacy = List.of(GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, new BlockPos(10, 20, 30)));
        if (!decodedLegacy.result().get().equals(expectedLegacy)) {
            helper.fail("GLOBAL_POS_LIST_CODEC decoded legacy BlockPos incorrectly. Expected: " + expectedLegacy
                    + ", got: " + decodedLegacy.result().get());
            return;
        }

        for (com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode mode : com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode
                .values()) {
            @SuppressWarnings("deprecation")
            var modeBuf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                    level.registryAccess());
            com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode.STREAM_CODEC.encode(modeBuf, mode);
            com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode decodedMode = com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode.STREAM_CODEC
                    .decode(modeBuf);
            if (decodedMode != mode) {
                helper.fail("GraphViewMode.STREAM_CODEC failed round-trip for " + mode + ", got: " + decodedMode);
                return;
            }
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testClipboardUiStateStreamCodecParity(GameTestHelper helper) {
        var level = helper.getLevel();

        var state = new com.mervyn.miforeman.goal.ClipboardUiState(3, 7.5, -2.25, 1.5f,
                com.mervyn.miforeman.goal.ClipboardUiState.GraphViewMode.ITEMS_ONLY,
                true, true, false, false, true);

        @SuppressWarnings("deprecation")
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                level.registryAccess());
        com.mervyn.miforeman.goal.ClipboardUiState.STREAM_CODEC.encode(buf, state);
        var decoded = com.mervyn.miforeman.goal.ClipboardUiState.STREAM_CODEC.decode(buf);

        if (!decoded.equals(state)) {
            helper.fail("STREAM_CODEC round-trip does not match original ClipboardUiState. A field was "
                    + "likely added to the record without updating STREAM_CODEC (or vice versa). Original: "
                    + state + ", decoded: " + decoded);
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testReadMIRecipes(GameTestHelper helper) {
        var level = helper.getLevel();
        var recipeManager = level.getRecipeManager();
        int totalRecipes = 0;

        MIForeman.LOGGER.info("Starting MI Recipe Registry Extraction feasibility spike in GameTest...");

        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> byType = RecipeGraphTraverser
                .groupMachineRecipesByType(recipeManager);

        for (var entry : byType.entrySet()) {
            var recipes = entry.getValue();
            MIForeman.LOGGER.info("Recipe Type: {} - Found {} recipes", entry.getKey(), recipes.size());
            totalRecipes += recipes.size();
            for (var recipeHolder : recipes) {
                var recipe = recipeHolder.value();
                MIForeman.LOGGER.info("  Recipe: {} ({} ticks, {} EU/t)", recipeHolder.id(), recipe.duration,
                        recipe.eu);
            }
        }

        MIForeman.LOGGER.info("Extraction spike complete. Total MI recipes read: {}", totalRecipes);

        if (totalRecipes == 0) {
            helper.fail("No MI recipes were found in the registry. Make sure Modern Industrialization is loaded.");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGenerateRequirementsComplex(GameTestHelper helper) {
        var level = helper.getLevel();

        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        double rate = 1.0;

        ProductionGoal goal = new ProductionGoal("quantum_plan", ProductionGoal.TargetType.ITEM, targetId, rate);

        MIForeman.LOGGER.info("Starting UC2 complex recipe graph traversal test for quantum_upgrade...");

        ProductionGoal.FactoryPlan plan = RecipeGraphTraverser.computePlan(level, goal);

        MIForeman.LOGGER.info("Machines calculated in plan:");
        for (var req : plan.machines()) {
            MIForeman.LOGGER.info("  - {}: count={}, baseEu={}, totalEu={}", req.machineId(), req.count(),
                    req.baseEuPerTick(), req.totalEuPerTick());
        }

        MIForeman.LOGGER.info("Raw inputs calculated in plan:");
        for (var flow : plan.rawInputs()) {
            MIForeman.LOGGER.info("  - {}: {}/s", flow.resourceId(), flow.rate());
        }

        ResourceLocation assemblerId = ResourceLocation.parse("modern_industrialization:assembler");
        double assemblerCount = plan.machines().stream()
                .filter(req -> req.machineId().equals(assemblerId))
                .mapToDouble(ProductionGoal.MachineRequirement::count)
                .sum();

        if (assemblerCount < 210.0) {
            helper.fail(
                    "Expected at least 210.0 assemblers for quantum_upgrade plan, but calculated: " + assemblerCount);
            return;
        }

        long totalPower = plan.totalPowerDemandEu();
        if (totalPower <= 0) {
            helper.fail("Expected positive total power demand for quantum_upgrade, but calculated: " + totalPower);
            return;
        }

        ResourceLocation uuMatterId = ResourceLocation.parse("modern_industrialization:uu_matter");
        double uuMatterRate = plan.rawInputs().stream()
                .filter(flow -> flow.resourceId().equals(uuMatterId))
                .mapToDouble(ProductionGoal.MaterialFlow::rate)
                .sum();

        if (Math.abs(uuMatterRate - 50.0) > 0.001) {
            helper.fail("Expected 50.0 uu_matter rate, but calculated: " + uuMatterRate);
            return;
        }

        ResourceLocation pvcId = ResourceLocation.parse("modern_industrialization:polyvinyl_chloride");
        double pvcRate = plan.rawInputs().stream()
                .filter(flow -> flow.resourceId().equals(pvcId))
                .mapToDouble(ProductionGoal.MaterialFlow::rate)
                .sum();

        if (Math.abs(pvcRate - 461500.0) > 0.001) {
            helper.fail("Expected 461500.0 polyvinyl_chloride rate, but calculated: " + pvcRate);
            return;
        }

        com.mervyn.miforeman.goal.RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        var pvcNode = graph.nodes().get(pvcId);
        if (pvcNode == null || Math.abs(pvcNode.getRequiredRate() - 461500.0) > 0.001) {
            helper.fail("Expected 461500.0 polyvinyl_chloride requiredRate on graph node, but calculated: "
                    + (pvcNode != null ? pvcNode.getRequiredRate() : "null"));
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testIdentifyBottlenecks(GameTestHelper helper) {
        var level = helper.getLevel();

        var compressorBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("modern_industrialization:bronze_compressor"));
        BlockPos relativePos = new BlockPos(1, 2, 1);
        BlockPos absolutePos = helper.absolutePos(relativePos);

        helper.setBlock(relativePos, compressorBlock);

        BlockEntity be = level.getBlockEntity(absolutePos);
        if (!(be instanceof MachineBlockEntity machine)) {
            helper.fail("Placed block is not a MachineBlockEntity!");
            return;
        }

        UnifiedCrafter crafter = ServerMonitoringManager.getCrafter(machine);
        if (crafter == null) {
            helper.fail("Placed Compressor does not have a CrafterComponent!");
            return;
        }

        var statusStarving = ServerMonitoringManager.getMachinePassiveStatusDetailed(crafter, level);
        if (statusStarving.status() != com.mervyn.miforeman.goal.MachineStatus.RED) {
            helper.fail("Expected machine with empty inputs to be STARVING (RED), but got: " + statusStarving.status());
            return;
        }
        if (statusStarving.reason() != com.mervyn.miforeman.goal.FailureReason.STARVED) {
            helper.fail("Expected empty-input RED machine to report STARVED (no cyclicResourceIds given), but got: "
                    + statusStarving.reason());
            return;
        }

        var ironIngot = net.minecraft.world.item.Items.IRON_INGOT;
        var inputSlot = crafter.getItemInputs().get(0);
        inputSlot.setKey(aztech.modern_industrialization.thirdparty.fabrictransfer.api.item.ItemVariant.of(ironIngot));
        inputSlot.setAmount(1);

        var outputSlot = ((UnifiedCrafter.StandardCrafterAdapter) crafter).getUnderlying().getInventory()
                .getItemOutputs().get(0);
        outputSlot.setKey(aztech.modern_industrialization.thirdparty.fabrictransfer.api.item.ItemVariant
                .of(net.minecraft.world.item.Items.GLASS));
        outputSlot.setAmount(64);

        var statusSaturating = ServerMonitoringManager.getMachinePassiveStatusDetailed(crafter, level);
        if (statusSaturating.status() != com.mervyn.miforeman.goal.MachineStatus.ORANGE) {
            helper.fail("Expected machine with matched inputs but blocked outputs to be SATURATING (ORANGE), but got: "
                    + statusSaturating.status());
            return;
        }
        if (statusSaturating.reason() != com.mervyn.miforeman.goal.FailureReason.CLOG_LOCK) {
            helper.fail("Expected blocked-output ORANGE machine to report CLOG_LOCK, but got: " + statusSaturating.reason());
            return;
        }

        outputSlot.empty();
        com.mervyn.miforeman.goal.MachineStatus statusReady = ServerMonitoringManager.getMachinePassiveStatus(crafter, level);
        if (statusReady != com.mervyn.miforeman.goal.MachineStatus.ORANGE) {
            helper.fail(
                    "Expected ready-to-craft machine to return ORANGE (since it can start but is not currently active), but got: "
                            + statusReady);
            return;
        }

        // MI resets a drained slot's resource type to blank, so the last known recipe stands in for it.
        inputSlot.setAmount(0);
        ResourceLocation lastKnownRecipeId = statusSaturating.matchedRecipeId();
        if (lastKnownRecipeId == null) {
            helper.fail("Test 2's ORANGE status didn't report a matchedRecipeId to build the DEAD_LOOP case from.");
            return;
        }
        ResourceLocation ironIngotId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ironIngot);

        var statusDeadLoop = ServerMonitoringManager.getMachinePassiveStatusDetailed(crafter, level,
                Set.of(ironIngotId), lastKnownRecipeId);
        if (statusDeadLoop.status() != com.mervyn.miforeman.goal.MachineStatus.RED) {
            helper.fail("Expected drained-input machine to be RED, but got: " + statusDeadLoop.status());
            return;
        }
        if (statusDeadLoop.reason() != com.mervyn.miforeman.goal.FailureReason.DEAD_LOOP) {
            helper.fail("Expected RED machine last seen running a recipe touching a cyclic resource to report "
                    + "DEAD_LOOP, but got: " + statusDeadLoop.reason());
            return;
        }

        var statusStillStarved = ServerMonitoringManager.getMachinePassiveStatusDetailed(crafter, level,
                Set.of(ResourceLocation.parse("minecraft:diamond")), lastKnownRecipeId);
        if (statusStillStarved.reason() != com.mervyn.miforeman.goal.FailureReason.STARVED) {
            helper.fail("Expected drained-input machine with an unrelated cyclicResourceIds entry to stay STARVED, but got: "
                    + statusStillStarved.reason());
            return;
        }

        // A machine that has never run is not dead-looping yet.
        var statusNeverRan = ServerMonitoringManager.getMachinePassiveStatusDetailed(crafter, level,
                Set.of(ironIngotId), null);
        if (statusNeverRan.reason() != com.mervyn.miforeman.goal.FailureReason.STARVED) {
            helper.fail("Expected drained-input machine with no lastKnownRecipeId to stay STARVED, but got: "
                    + statusNeverRan.reason());
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testCycleRecipePlan(GameTestHelper helper) {
        var level = helper.getLevel();

        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal goal = new ProductionGoal("cycle_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);

        var initialGraph = RecipeGraphTraverser.computeRecipeGraph(level, goal);

        var ambiguousNode = initialGraph.nodes().values().stream()
                .filter(node -> node.getType() != com.mervyn.miforeman.goal.NodeType.MACHINE
                        && node.getAmbiguityOptions().size() > 1)
                .findFirst()
                .orElse(null);

        if (ambiguousNode == null) {
            helper.fail("Expected at least one ambiguous resource node in quantum_upgrade recipe graph.");
            return;
        }

        List<ResourceLocation> options = ambiguousNode.getAmbiguityOptions();
        ResourceLocation firstRecipe = options.get(0);
        ResourceLocation secondRecipe = options.get(1);

        if (!firstRecipe.equals(ambiguousNode.getSelectedAmbiguity())) {
            helper.fail("Expected initial ambiguity selection to be " + firstRecipe + ", but was: "
                    + ambiguousNode.getSelectedAmbiguity());
            return;
        }

        java.util.Map<ResourceLocation, ResourceLocation> selections = new java.util.HashMap<>(goal.recipeSelections());
        selections.put(ambiguousNode.getAmbiguityOwnerId(), secondRecipe);

        ProductionGoal updatedGoal = new ProductionGoal(
                goal.name(), goal.type(), goal.targetId(), goal.rate(),
                selections, java.util.Optional.empty(), goal.perHour(),
                goal.threshold(), goal.linkedMachines());
        var updatedGraph = RecipeGraphTraverser.computeRecipeGraph(level, updatedGoal);

        boolean hasFirstRecipe = updatedGraph.nodes().containsKey(firstRecipe);
        boolean hasSecondRecipe = updatedGraph.nodes().containsKey(secondRecipe);

        if (hasFirstRecipe) {
            helper.fail("After cycling recipe to " + secondRecipe + ", the graph still contained the default recipe "
                    + firstRecipe);
            return;
        }

        if (!hasSecondRecipe) {
            helper.fail("After cycling recipe to " + secondRecipe
                    + ", the graph did not contain the selected recipe node.");
            return;
        }

        var updatedResNode = updatedGraph.nodes().get(ambiguousNode.getId());
        if (updatedResNode == null) {
            helper.fail("Resource node " + ambiguousNode.getId() + " is missing from the updated graph.");
            return;
        }

        if (!secondRecipe.equals(updatedResNode.getSelectedAmbiguity())) {
            helper.fail("Expected selected ambiguity to be " + secondRecipe + ", but got: "
                    + updatedResNode.getSelectedAmbiguity());
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testBuildRecipeIndex(GameTestHelper helper) {
        var level = helper.getLevel();

        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal goal = new ProductionGoal("recipe_index_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);

        var graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);

        java.util.Set<ResourceLocation> recipeIndex = com.mervyn.miforeman.goal.MachineScanner.buildRecipeIndex(graph);

        long expectedMachineNodeCount = graph.nodes().values().stream()
                .filter(node -> node.getType() == com.mervyn.miforeman.goal.NodeType.MACHINE)
                .count();

        if (expectedMachineNodeCount == 0) {
            helper.fail("Expected at least one MACHINE-type node in the quantum_upgrade graph.");
            return;
        }

        if (recipeIndex.size() != expectedMachineNodeCount) {
            helper.fail("Expected recipe index to contain exactly " + expectedMachineNodeCount
                    + " entries (one per MACHINE node), but got: " + recipeIndex.size());
            return;
        }

        for (var node : graph.nodes().values()) {
            if (node.getType() == com.mervyn.miforeman.goal.NodeType.MACHINE && !recipeIndex.contains(node.getId())) {
                helper.fail("Recipe index is missing MACHINE node id: " + node.getId());
                return;
            }
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testRecipeGraphEdgesAreDeduped(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal goal = new ProductionGoal("dedup_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);
        var graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);

        java.util.Set<List<ResourceLocation>> seenPairs = new java.util.HashSet<>();
        for (var edge : graph.edges()) {
            List<ResourceLocation> pair = List.of(edge.from(), edge.to());
            if (!seenPairs.add(pair)) {
                helper.fail("Duplicate (from,to) edge found: " + edge.from() + " -> " + edge.to()
                        + ". edges.merge() should make this structurally impossible.");
                return;
            }
        }

        var propagated = RecipeGraphTraverser.computeRecipeGraphUncached(level, goal, RecipeGraphTraverser.SolveMode.PROPAGATION_ONLY);
        if (propagated.edges().size() != 780) {
            helper.fail("Expected 780 demand edges in the propagated quantum_upgrade graph, but got: " + propagated.edges().size()
                    + ". If this changed intentionally, e.g. an MI recipe update, update this snapshot.");
            return;
        }
        java.util.Set<List<ResourceLocation>> demandPairs = new java.util.HashSet<>();
        for (var edge : propagated.edges())
            demandPairs.add(List.of(edge.from(), edge.to()));
        for (var edge : graph.edges()) {
            if (demandPairs.contains(List.of(edge.from(), edge.to())))
                continue;
            var from = graph.node(edge.from());
            var to = graph.node(edge.to());
            if (from == null || to == null || from.getType() != com.mervyn.miforeman.goal.NodeType.MACHINE
                    || to.getType() == com.mervyn.miforeman.goal.NodeType.MACHINE) {
                helper.fail("The plan LP added an edge that isn't machine -> resource: " + edge);
                return;
            }
        }
        if (graph.edges().size() != 790) {
            helper.fail("Expected 790 edges (780 demand + 10 byproduct) in the quantum_upgrade graph, but got: "
                    + graph.edges().size() + ". If this changed intentionally, update this snapshot.");
            return;
        }
        if (graph.nodes().size() != 536) {
            helper.fail("Expected 536 nodes in the quantum_upgrade graph, but got: " + graph.nodes().size()
                    + ". If this changed intentionally, e.g. an MI recipe update, update this snapshot.");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testAmbiguityOwnerIdIsConsistent(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal goal = new ProductionGoal("ambiguity_owner_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);
        var graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);

        for (var node : graph.nodes().values()) {
            if (node.getAmbiguityOptions().isEmpty()) {
                continue;
            }

            if (node.getType() != com.mervyn.miforeman.goal.NodeType.MACHINE) {
                if (!node.getId().equals(node.getAmbiguityOwnerId())) {
                    helper.fail("Resource node " + node.getId() + " should own its own ambiguity data, but "
                            + "ambiguityOwnerId was: " + node.getAmbiguityOwnerId());
                    return;
                }
                continue;
            }

            ResourceLocation owner = node.getAmbiguityOwnerId();
            if (owner == null) {
                helper.fail("MACHINE node " + node.getId() + " has ambiguity options but no ambiguityOwnerId.");
                return;
            }

            boolean ownerIsARealOutput = node.getOutputs().stream().anyMatch(edge -> edge.to().equals(owner));
            if (!ownerIsARealOutput) {
                helper.fail("MACHINE node " + node.getId() + "'s ambiguityOwnerId (" + owner
                        + ") is not one of its actual output resourceIds: "
                        + node.getOutputs().stream().map(e -> e.to().toString()).toList());
                return;
            }
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testCollectUpstreamResourceIds(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal goal = new ProductionGoal("upstream_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);
        var graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);

        var machineNode = graph.nodes().values().stream()
                .filter(n -> n.getType() == com.mervyn.miforeman.goal.NodeType.MACHINE)
                .findFirst()
                .orElse(null);
        if (machineNode == null) {
            helper.fail("Expected at least one MACHINE node in the quantum_upgrade graph.");
            return;
        }

        var upstream = RecipeGraphTraverser.collectUpstreamResourceIds(graph, machineNode.getId());

        if (!upstream.contains(targetId)) {
            helper.fail("Expected collectUpstreamResourceIds to include the goal's target resource " + targetId
                    + ", but got: " + upstream);
            return;
        }

        ResourceLocation immediateOutput = machineNode.getOutputs().stream()
                .findFirst().map(com.mervyn.miforeman.goal.GraphEdge::to).orElse(null);
        if (immediateOutput == null) {
            helper.fail("Test machine node unexpectedly has no output edges; can't verify immediate-output inclusion.");
            return;
        }
        if (!upstream.contains(immediateOutput)) {
            helper.fail("Expected collectUpstreamResourceIds to include the machine's immediate output resource "
                    + immediateOutput + ", but got: " + upstream);
            return;
        }

        for (ResourceLocation id : upstream) {
            var node = graph.nodes().get(id);
            if (node != null && node.getType() == com.mervyn.miforeman.goal.NodeType.MACHINE) {
                helper.fail("Expected collectUpstreamResourceIds to only return resource ids, but got a MACHINE "
                        + "node id in the result: " + id);
                return;
            }
        }

        var unknown = RecipeGraphTraverser.collectUpstreamResourceIds(graph,
                ResourceLocation.parse("modern_industrialization:not_a_real_recipe"));
        if (!unknown.isEmpty()) {
            helper.fail("Expected an unknown recipe id to produce an empty upstream set, but got: " + unknown);
            return;
        }
        var nullResult = RecipeGraphTraverser.collectUpstreamResourceIds(graph, null);
        if (!nullResult.isEmpty()) {
            helper.fail("Expected a null recipe id to produce an empty upstream set, but got: " + nullResult);
            return;
        }

        helper.succeed();
    }

    private static com.mervyn.miforeman.goal.lp.Simplex.Row lpRow(double rhs, double... coefficients) {
        return new com.mervyn.miforeman.goal.lp.Simplex.Row(coefficients, rhs);
    }

    private static com.mervyn.miforeman.goal.lp.Simplex.Solution lpSolve(double[] maximize,
            List<com.mervyn.miforeman.goal.lp.Simplex.Row> equalities, List<com.mervyn.miforeman.goal.lp.Simplex.Row> upperBounds) {
        return com.mervyn.miforeman.goal.lp.Simplex.solve(
                new com.mervyn.miforeman.goal.lp.Simplex.Program(maximize, equalities, upperBounds));
    }

    private static boolean lpNear(double a, double b) {
        return Math.abs(a - b) <= 1e-7 * (1 + Math.abs(b));
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testSimplexKnownOptimaAndDuals(GameTestHelper helper) {
        var optimal = com.mervyn.miforeman.goal.lp.Simplex.Status.OPTIMAL;
        // Textbook: max 3x + 5y, x <= 4, 2y <= 12, 3x + 2y <= 18 -> 36 at (2, 6), duals (0, 1.5, 1).
        var textbook = lpSolve(new double[] { 3, 5 }, List.of(),
                List.of(lpRow(4, 1, 0), lpRow(12, 0, 2), lpRow(18, 3, 2)));
        if (textbook.status() != optimal || !lpNear(textbook.objective(), 36) || !lpNear(textbook.x()[0], 2)
                || !lpNear(textbook.x()[1], 6)) {
            helper.fail("Textbook LP should reach 36 at (2, 6), got " + textbook.status() + " " + textbook.objective()
                    + " " + java.util.Arrays.toString(textbook.x()));
            return;
        }
        double[] duals = textbook.upperBoundDuals();
        if (!lpNear(duals[0], 0) || !lpNear(duals[1], 1.5) || !lpNear(duals[2], 1)) {
            helper.fail("Textbook duals should be (0, 1.5, 1), got " + java.util.Arrays.toString(duals));
            return;
        }

        // Equality rows: max x + y with x + 2y = 4, x <= 3 -> 3.5 at (3, 0.5).
        var withEquality = lpSolve(new double[] { 1, 1 }, List.of(lpRow(4, 1, 2)), List.of(lpRow(3, 1, 0)));
        if (withEquality.status() != optimal || !lpNear(withEquality.objective(), 3.5)) {
            helper.fail("Equality LP should reach 3.5, got " + withEquality.status() + " " + withEquality.objective());
            return;
        }

        // A <= row with negative rhs is a >= row: max -x with x >= 2 -> x = 2.
        var flipped = lpSolve(new double[] { -1 }, List.of(), List.of(lpRow(-2, -1)));
        if (flipped.status() != optimal || !lpNear(flipped.x()[0], 2)) {
            helper.fail("x >= 2 minimising x should give x = 2, got " + flipped.status() + " "
                    + java.util.Arrays.toString(flipped.x()));
            return;
        }

        var infeasible = lpSolve(new double[] { 1, 1 }, List.of(lpRow(5, 1, 1)), List.of(lpRow(1, 1, 0), lpRow(1, 0, 1)));
        if (infeasible.status() != com.mervyn.miforeman.goal.lp.Simplex.Status.INFEASIBLE) {
            helper.fail("x + y = 5 with x, y <= 1 should be infeasible, got " + infeasible.status());
            return;
        }
        var unbounded = lpSolve(new double[] { 1, 0 }, List.of(), List.of(lpRow(1, 1, -1)));
        if (unbounded.status() != com.mervyn.miforeman.goal.lp.Simplex.Status.UNBOUNDED) {
            helper.fail("max x with x - y <= 1 should be unbounded, got " + unbounded.status());
            return;
        }

        // A row only satisfiable at zero (-x - y = 0) can't take the perturbation, but the program is still feasible.
        var zeroRow = lpSolve(new double[] { 0, 0, 1 }, List.of(lpRow(1, 1, 0, 1), lpRow(0, -1, -1, 0)), List.of());
        if (zeroRow.status() != optimal || !lpNear(zeroRow.objective(), 1)) {
            helper.fail("x + z = 1, -x - y = 0 is feasible (x = y = 0, z = 1), got " + zeroRow.status());
            return;
        }

        // Beale's example cycles forever under plain Dantzig pivoting. The Bland fallback must end it at 1.25.
        var beale = lpSolve(new double[] { 0.75, -20, 0.5, -6 }, List.of(), List.of(
                lpRow(0, 0.25, -8, -1, 9), lpRow(0, 0.5, -12, -0.5, 3), lpRow(1, 0, 0, 1, 0)));
        if (beale.status() != optimal || !lpNear(beale.objective(), 1.25)) {
            helper.fail("Beale's cycling LP should finish at 1.25, got " + beale.status() + " " + beale.objective());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testStagedLpLocksEarlierStages(GameTestHelper helper) {
        // Stage 1: max x + y with x + y <= 4, x <= 3. Stage 2 then prefers x, but must keep x + y = 4.
        var result = com.mervyn.miforeman.goal.lp.StagedLp.solve(List.of(), List.of(lpRow(4, 1, 1), lpRow(3, 1, 0)), List.of(
                com.mervyn.miforeman.goal.lp.StagedLp.Stage.maximize(new double[] { 1, 1 }),
                com.mervyn.miforeman.goal.lp.StagedLp.Stage.minimize(new double[] { 0, 1 })));
        if (!result.ok() || result.stagesSolved() != 2) {
            helper.fail("Both stages should solve, got " + result.stagesSolved());
            return;
        }
        double[] x = result.solution().x();
        // The lock is a 1e-7 relative band, so stage 2 may shave that much off stage 1's total.
        if (x[0] + x[1] < 4 - 1e-6 || Math.abs(x[0] - 3) > 1e-6 || Math.abs(x[1] - 1) > 1e-6) {
            helper.fail("Stage 2 must not give up stage 1's total of 4: got " + java.util.Arrays.toString(x));
            return;
        }
        helper.succeed();
    }

    private static com.mervyn.miforeman.goal.PlanSolver.Recipe planRecipe(String id, double duration,
            Map<String, Double> inputs, Map<String, Double> outputs) {
        return new com.mervyn.miforeman.goal.PlanSolver.Recipe(id, duration, inputs, outputs);
    }

    /** Null when every resource balances: produced + imported + free loop supply - consumed - surplus = demand. */
    private static String planImbalance(com.mervyn.miforeman.goal.PlanSolver.Model model,
            com.mervyn.miforeman.goal.PlanSolver.Result result) {
        Map<String, Double> net = new java.util.TreeMap<>();
        Map<String, Double> scale = new java.util.TreeMap<>();
        for (var recipe : model.recipes()) {
            double runs = result.runs().getOrDefault(recipe.id(), 0.0);
            recipe.outputs().forEach((k, v) -> { net.merge(k, runs * v, Double::sum); scale.merge(k, Math.abs(runs * v), Double::sum); });
            recipe.inputs().forEach((k, v) -> { net.merge(k, -runs * v, Double::sum); scale.merge(k, Math.abs(runs * v), Double::sum); });
        }
        result.imports().forEach((k, v) -> net.merge(k, v, Double::sum));
        result.loopSupply().forEach((k, v) -> net.merge(k, v, Double::sum));
        result.surplus().forEach((k, v) -> net.merge(k, -v, Double::sum));
        for (var e : net.entrySet()) {
            double demand = e.getKey().equals(model.target()) ? model.targetRate() : 0.0;
            if (Math.abs(e.getValue() - demand) > 1e-6 * (1 + scale.getOrDefault(e.getKey(), 0.0) + demand))
                return e.getKey() + " nets " + e.getValue() + " against a demand of " + demand;
        }
        for (var v : result.runs().values())
            if (v < 0)
                return "a negative run rate " + v;
        return null;
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testPlanSolverSyntheticCases(GameTestHelper helper) {
        // Byproduct netting: making A also gives off H, which the target recipe needs.
        var netting = new com.mervyn.miforeman.goal.PlanSolver.Model(List.of(
                planRecipe("t", 100, Map.of("item:a", 1.0, "item:h", 1.0), Map.of("item:t", 1.0)),
                planRecipe("a", 100, Map.of("item:x", 1.0), Map.of("item:a", 1.0, "item:h", 1.0))),
                Set.of("item:x", "item:h"), "item:t", 10.0, List.of());
        var nettingResult = com.mervyn.miforeman.goal.PlanSolver.solve(netting);
        if (nettingResult == null || nettingResult.imports().get("item:h") > 1e-9 || planImbalance(netting, nettingResult) != null) {
            helper.fail("A byproduct the plan needs should be used before importing it: "
                    + (nettingResult == null ? "no solution" : nettingResult.imports() + " / " + planImbalance(netting, nettingResult)));
            return;
        }

        // Co-production: one electrolyser run gives both H and O, so it runs once per target, not twice.
        var coProduct = new com.mervyn.miforeman.goal.PlanSolver.Model(List.of(
                planRecipe("t", 100, Map.of("item:h", 2.0, "item:o", 1.0), Map.of("item:t", 1.0)),
                planRecipe("e", 100, Map.of("fluid:w", 2.0), Map.of("item:h", 2.0, "item:o", 1.0))),
                Set.of("fluid:w"), "item:t", 5.0, List.of());
        var coResult = com.mervyn.miforeman.goal.PlanSolver.solve(coProduct);
        if (coResult == null || Math.abs(coResult.runs().get("e") - 5.0) > 1e-9) {
            helper.fail("Co-produced H and O should share one electrolyser run per target: "
                    + (coResult == null ? "no solution" : coResult.runs()));
            return;
        }

        // Nuggets can be bought, so the ingot and nugget loop must not run.
        var packer = planRecipe("pack", 100, Map.of("item:nugget", 9.0), Map.of("item:ingot", 1.0));
        var unpacker = planRecipe("unpack", 100, Map.of("item:ingot", 1.0), Map.of("item:nugget", 9.0));
        var backEdge = new com.mervyn.miforeman.goal.PlanSolver.BackEdge("unpack", "item:ingot");
        var sourced = new com.mervyn.miforeman.goal.PlanSolver.Model(List.of(packer, unpacker), Set.of("item:nugget"),
                "item:ingot", 4.0, List.of(backEdge));
        var sourcedResult = com.mervyn.miforeman.goal.PlanSolver.solve(sourced);
        if (sourcedResult == null || sourcedResult.runs().get("unpack") > 1e-9 || !sourcedResult.unsourced().isEmpty()
                || planImbalance(sourced, sourcedResult) != null) {
            helper.fail("A loop with an outside source should not run or be flagged: "
                    + (sourcedResult == null ? "no solution" : sourcedResult.runs() + " " + sourcedResult.unsourced()));
            return;
        }

        // The same loop with no way in is supplied on its back-edge and flagged.
        var unsourced = new com.mervyn.miforeman.goal.PlanSolver.Model(List.of(packer, unpacker), Set.of(),
                "item:ingot", 4.0, List.of(backEdge));
        var unsourcedResult = com.mervyn.miforeman.goal.PlanSolver.solve(unsourced);
        if (unsourcedResult == null || !unsourcedResult.unsourced().contains("item:ingot")
                || Math.abs(unsourcedResult.runs().get("pack") - 4.0) > 1e-9 || planImbalance(unsourced, unsourcedResult) != null) {
            helper.fail("A loop with no way in should be flagged and still plan its machines: "
                    + (unsourcedResult == null ? "no solution" : unsourcedResult.runs() + " " + unsourcedResult.unsourced()));
            return;
        }

        // A recipe returning more of its input than it eats needs none of that input from outside,
        // and the excess is surplus, never a negative import.
        var selfFeeding = new com.mervyn.miforeman.goal.PlanSolver.Model(List.of(
                planRecipe("dup", 100, Map.of("item:y", 1.0), Map.of("item:y", 2.0, "item:t", 1.0))),
                Set.of("item:y"), "item:t", 3.0, List.of());
        var selfResult = com.mervyn.miforeman.goal.PlanSolver.solve(selfFeeding);
        if (selfResult == null || selfResult.imports().get("item:y") > 1e-9
                || Math.abs(selfResult.surplus().get("item:y") - 3.0) > 1e-9) {
            helper.fail("A self-feeding recipe should import nothing and leave its excess as surplus: "
                    + (selfResult == null ? "no solution" : selfResult.imports() + " " + selfResult.surplus()));
            return;
        }

        // An item byproduct never covers a same-named fluid demand.
        var collision = new com.mervyn.miforeman.goal.PlanSolver.Model(List.of(
                planRecipe("t", 100, Map.of("fluid:h", 1000.0, "item:a", 1.0), Map.of("item:t", 1.0)),
                planRecipe("a", 100, Map.of("item:x", 1.0), Map.of("item:a", 1.0, "item:h", 1.0))),
                Set.of("item:x", "fluid:h"), "item:t", 2.0, List.of());
        var collisionResult = com.mervyn.miforeman.goal.PlanSolver.solve(collision);
        if (collisionResult == null || Math.abs(collisionResult.imports().get("fluid:h") - 2000.0) > 1e-6) {
            helper.fail("An item byproduct reduced a same-named fluid import: "
                    + (collisionResult == null ? "no solution" : collisionResult.imports()));
            return;
        }

        // Model order never changes the answer or the pivots taken.
        var reordered = new com.mervyn.miforeman.goal.PlanSolver.Model(List.of(unpacker, packer), Set.of("item:nugget"),
                "item:ingot", 4.0, List.of(backEdge));
        var reorderedResult = com.mervyn.miforeman.goal.PlanSolver.solve(reordered);
        if (reorderedResult == null || !reorderedResult.runs().equals(sourcedResult.runs())
                || reorderedResult.pivots() != sourcedResult.pivots()) {
            helper.fail("Reordering the model changed the plan: " + sourcedResult.runs() + " vs "
                    + (reorderedResult == null ? "no solution" : reorderedResult.runs()));
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID, timeoutTicks = 600)
    public static void testPlanLpMatchesPropagationWhereItWasExact(GameTestHelper helper) {
        var level = helper.getLevel();
        // Only these two plans of 7+ nodes have no loop or shared output. If an MI update couples them, find replacements.
        List<String> candidates = List.of("modern_industrialization:cadmium_rod", "modern_industrialization:cadmium_tiny_dust");
        int compared = 0;
        for (String id : candidates) {
            var goal = new ProductionGoal("lp_regression", ProductionGoal.TargetType.ITEM, ResourceLocation.parse(id), 30.0);
            if (RecipeGraphTraverser.planIsCoupled(level, goal))
                continue;
            compared++;
            var propagated = RecipeGraphTraverser.computeRecipeGraphUncached(level, goal, RecipeGraphTraverser.SolveMode.PROPAGATION_ONLY);
            var solved = RecipeGraphTraverser.computeRecipeGraphUncached(level, goal, RecipeGraphTraverser.SolveMode.FORCE_LP);
            for (var node : propagated.nodes().values()) {
                var other = solved.node(node.getId());
                if (other == null || Math.abs(other.getRequiredRate() - node.getRequiredRate()) > 1e-6 * (1 + node.getRequiredRate())
                        || Math.abs(other.getMachineCount() - node.getMachineCount()) > 1e-6 * (1 + node.getMachineCount())) {
                    helper.fail(id + ": the LP disagrees with the exact propagation at " + node.getId() + ": "
                            + node.getRequiredRate() + "/" + node.getMachineCount() + " vs "
                            + (other == null ? "missing" : other.getRequiredRate() + "/" + other.getMachineCount()));
                    return;
                }
            }
            if (propagated.edges().size() != solved.edges().size()) {
                helper.fail(id + ": the LP changed the structure of a graph with no loops or shared outputs.");
                return;
            }
        }
        if (compared < candidates.size()) {
            helper.fail("Only " + compared + " of the pinned uncoupled targets are still uncoupled; rescan for replacements.");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID, timeoutTicks = 600)
    public static void testPlanLpConservesEveryResource(GameTestHelper helper) {
        var level = helper.getLevel();
        for (String id : List.of("modern_industrialization:iron_plate", "modern_industrialization:quantum_upgrade")) {
            var goal = new ProductionGoal("lp_conservation", ProductionGoal.TargetType.ITEM, ResourceLocation.parse(id), 60.0);
            var model = RecipeGraphTraverser.planModel(level, goal);
            long start = System.nanoTime();
            var result = com.mervyn.miforeman.goal.PlanSolver.solve(model);
            long micros = (System.nanoTime() - start) / 1000;
            if (result == null) {
                helper.fail(id + ": the plan LP found no solution.");
                return;
            }
            MIForeman.LOGGER.info("Plan LP for {}: {} recipes, {} pivots, {} us", id, model.recipes().size(), result.pivots(), micros);
            String imbalance = planImbalance(model, result);
            if (imbalance != null) {
                helper.fail(id + ": " + imbalance);
                return;
            }
            // About 1,700 pivots on quantum_upgrade today. The ceiling catches a solver that stops converging.
            if (result.pivots() > 5000) {
                helper.fail(id + ": the plan LP took " + result.pivots() + " pivots, past the 5000 ceiling.");
                return;
            }
            // Client and server build the model separately, so its order must never matter.
            List<com.mervyn.miforeman.goal.PlanSolver.Recipe> shuffled = new java.util.ArrayList<>(model.recipes());
            java.util.Collections.shuffle(shuffled, new java.util.Random(42));
            var reordered = com.mervyn.miforeman.goal.PlanSolver.solve(new com.mervyn.miforeman.goal.PlanSolver.Model(
                    shuffled, model.raw(), model.target(), model.targetRate(), model.backEdges()));
            if (reordered == null || !reordered.runs().equals(result.runs()) || reordered.pivots() != result.pivots()) {
                helper.fail(id + ": shuffling the model's recipe order changed the plan or the pivots taken.");
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testPlanFallsBackWhenTheLpFails(GameTestHelper helper) {
        var level = helper.getLevel();
        var goal = new ProductionGoal("lp_fallback", ProductionGoal.TargetType.ITEM,
                ResourceLocation.parse("modern_industrialization:iron_plate"), 60.0,
                Map.of(ResourceLocation.parse("minecraft:iron_ingot"), IRON_INGOT_PACKER), Optional.empty());
        var propagated = RecipeGraphTraverser.computeRecipeGraphUncached(level, goal, RecipeGraphTraverser.SolveMode.PROPAGATION_ONLY);
        var failed = RecipeGraphTraverser.computeRecipeGraphUncached(level, goal, RecipeGraphTraverser.SolveMode.FAIL_LP);
        if (failed.nodes().size() != propagated.nodes().size() || failed.edges().size() != propagated.edges().size()) {
            helper.fail("A failed LP must leave the propagated graph intact.");
            return;
        }
        for (var node : propagated.nodes().values()) {
            if (failed.node(node.getId()).getMachineCount() != node.getMachineCount()
                    || failed.node(node.getId()).getRequiredRate() != node.getRequiredRate()) {
                helper.fail("A failed LP changed " + node.getId() + "'s rates instead of keeping the propagated ones.");
                return;
            }
        }
        // Zero-rate flows never reach the plan lists, so nothing shows as "0/min".
        var acid = RecipeGraphTraverser.computePlan(level, new ProductionGoal("lp_zero_flows", ProductionGoal.TargetType.FLUID,
                ResourceLocation.parse("modern_industrialization:sulfuric_acid"), 10.0));
        for (var flow : acid.rawInputs()) {
            if (flow.rate() <= 0) {
                helper.fail("A zero-rate raw input reached the plan: " + flow);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testCapacitySolverFindsTheLimit(GameTestHelper helper) {
        // 100-tick recipes: one machine is 12 runs a minute. T takes one A per run.
        var chain = new com.mervyn.miforeman.goal.PlanSolver.Model(List.of(
                planRecipe("t", 100, Map.of("item:a", 1.0), Map.of("item:t", 1.0)),
                planRecipe("a", 100, Map.of("item:x", 1.0), Map.of("item:a", 1.0))),
                Set.of("item:x"), "item:t", 60.0, List.of());
        Set<String> needed = Set.of("t", "a");
        var limited = com.mervyn.miforeman.goal.CapacitySolver.solve(chain, Map.of("t", 2, "a", 1), needed);
        if (limited == null || Math.abs(limited.maxRate() - 12) > 1e-6 || !limited.bottlenecks().equals(Set.of("a"))
                || Math.abs(limited.utilisation().get("t") - 0.5) > 1e-6) {
            helper.fail("2 T machines and 1 A machine should make 12/min, limited by A: " + limited);
            return;
        }
        var moreA = com.mervyn.miforeman.goal.CapacitySolver.solve(chain, Map.of("t", 2, "a", 2), needed);
        var moreT = com.mervyn.miforeman.goal.CapacitySolver.solve(chain, Map.of("t", 3, "a", 1), needed);
        if (moreA == null || Math.abs(moreA.maxRate() - 24) > 1e-6 || moreT == null || Math.abs(moreT.maxRate() - 12) > 1e-6) {
            helper.fail("A machine at the bottleneck should raise output and one elsewhere shouldn't: " + moreA + " / " + moreT);
            return;
        }

        var blocked = com.mervyn.miforeman.goal.CapacitySolver.solve(chain, Map.of("t", 2), needed);
        if (blocked == null || blocked.maxRate() > 1e-9 || !blocked.blockers().equals(Set.of("a"))) {
            helper.fail("With no A machine the output should be 0 and A a blocker: " + blocked);
            return;
        }

        // Making B costs half an X and buying it a whole one, so B's recipe runs flat out. B can always be bought, so A is the limit.
        var importable = new com.mervyn.miforeman.goal.PlanSolver.Model(List.of(
                planRecipe("t", 100, Map.of("item:a", 1.0, "item:b", 1.0), Map.of("item:t", 1.0)),
                planRecipe("a", 100, Map.of("item:x", 1.0), Map.of("item:a", 1.0)),
                planRecipe("b", 100, Map.of("item:x", 0.5), Map.of("item:b", 1.0))),
                Set.of("item:x", "item:b"), "item:t", 60.0, List.of());
        var probed = com.mervyn.miforeman.goal.CapacitySolver.solve(importable, Map.of("t", 3, "a", 1, "b", 1), Set.of("t", "a", "b"));
        if (probed == null || probed.utilisation().getOrDefault("b", 0.0) < 1 - 1e-6 || !probed.bottlenecks().equals(Set.of("a"))) {
            helper.fail("A full recipe whose product can also be bought isn't a bottleneck: " + probed);
            return;
        }

        // Two caps that only bind together, so both are the limit.
        var joint = new com.mervyn.miforeman.goal.PlanSolver.Model(List.of(
                planRecipe("t", 100, Map.of("item:a", 1.0, "item:b", 1.0), Map.of("item:t", 1.0)),
                planRecipe("a", 100, Map.of("item:x", 1.0), Map.of("item:a", 1.0)),
                planRecipe("b", 100, Map.of("item:x", 1.0), Map.of("item:b", 1.0))),
                Set.of("item:x"), "item:t", 60.0, List.of());
        var jointResult = com.mervyn.miforeman.goal.CapacitySolver.solve(joint, Map.of("t", 3, "a", 1, "b", 1), Set.of("t", "a", "b"));
        if (jointResult == null || !jointResult.bottlenecks().equals(Set.of("a", "b"))) {
            helper.fail("Caps that only bind together should all be reported: " + jointResult);
            return;
        }

        var level = helper.getLevel();
        var graph = RecipeGraphTraverser.computeRecipeGraph(level, new ProductionGoal("capacity", ProductionGoal.TargetType.ITEM,
                ResourceLocation.parse("modern_industrialization:iron_plate"), 60.0));
        Map<String, Integer> oneEach = new java.util.TreeMap<>();
        Set<String> realNeeded = new java.util.TreeSet<>();
        for (var node : graph.nodes().values()) {
            if (node.getType() == com.mervyn.miforeman.goal.NodeType.MACHINE && node.getMachineCount() > 1e-9) {
                oneEach.put(node.getId().toString(), 1);
                realNeeded.add(node.getId().toString());
            }
        }
        var real = com.mervyn.miforeman.goal.CapacitySolver.solve(graph.planModel(), oneEach, realNeeded);
        if (real == null || real.maxRate() <= 0 || (real.bottlenecks().isEmpty() && real.blockers().isEmpty())) {
            helper.fail("iron_plate with one machine per recipe should make something and name a limit: " + real);
            return;
        }
        helper.succeed();
    }

    private static final ResourceLocation IRON_INGOT_PACKER = ResourceLocation.parse("modern_industrialization:materials/iron/packer/ingot");

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testDeadLoopSolverPieces(GameTestHelper helper) {
        var pack = planRecipe("pack", 100, Map.of("item:nugget", 9.0), Map.of("item:ingot", 1.0));
        var unpack = planRecipe("unpack", 100, Map.of("item:ingot", 1.0), Map.of("item:nugget", 9.0));
        var smelt = planRecipe("smelt", 100, Map.of("item:ore", 1.0), Map.of("item:ingot", 1.0));
        var loopEdge = new com.mervyn.miforeman.goal.PlanSolver.BackEdge("unpack", "item:ingot");

        if (!com.mervyn.miforeman.goal.PlanSolver.obtainable(List.of(pack, unpack), Set.of(), List.of()).isEmpty()) {
            helper.fail("A closed loop with no raw input must make nothing.");
            return;
        }
        if (!com.mervyn.miforeman.goal.PlanSolver.obtainable(List.of(pack, unpack), Set.of(), List.of(loopEdge))
                .containsAll(Set.of("item:ingot", "item:nugget"))) {
            helper.fail("A free edge should start the loop.");
            return;
        }
        if (!com.mervyn.miforeman.goal.PlanSolver.obtainable(List.of(pack, unpack, smelt), Set.of("item:ore"), List.of())
                .containsAll(Set.of("item:ingot", "item:nugget"))) {
            helper.fail("A loop fed from raw ore should make both its resources.");
            return;
        }
        var templated = planRecipe("templated", 100, Map.of("item:ore", 1.0, "item:template", 0.0), Map.of("item:plate", 1.0));
        if (!com.mervyn.miforeman.goal.PlanSolver.obtainable(List.of(templated), Set.of("item:ore"), List.of()).contains("item:plate")) {
            helper.fail("A zero-amount input must not block a recipe.");
            return;
        }

        var dead = com.mervyn.miforeman.goal.PlanSolver.loopSupply(new com.mervyn.miforeman.goal.PlanSolver.Model(
                List.of(pack, unpack), Set.of(), "item:ingot", 10.0, List.of(loopEdge)));
        var fed = com.mervyn.miforeman.goal.PlanSolver.loopSupply(new com.mervyn.miforeman.goal.PlanSolver.Model(
                List.of(pack, unpack, smelt), Set.of("item:ore"), "item:ingot", 10.0, List.of(loopEdge)));
        if (dead == null || dead.supply().getOrDefault(loopEdge, 0.0) <= 0 || fed == null || !fed.supply().isEmpty()) {
            helper.fail("Only the unfed loop should need free supply: unfed " + dead + ", fed " + fed);
            return;
        }
        double fluidCost = com.mervyn.miforeman.goal.PlanSolver.loopCost(
                Map.of(new com.mervyn.miforeman.goal.PlanSolver.BackEdge("r", "fluid:acid"), 1000.0));
        if (Math.abs(fluidCost - 1.0) > 1e-9 || com.mervyn.miforeman.goal.PlanSolver.loopCost(dead.supply()) <= 0) {
            helper.fail("Loop cost should weigh fluids per bucket like imports, got " + fluidCost);
            return;
        }

        var electrolyse = planRecipe("electrolyse", 100, Map.of("fluid:acid", 500.0), Map.of("item:sulfur", 1.0, "fluid:water", 200.0));
        if (!com.mervyn.miforeman.goal.PlanSolver.makesOnly(smelt, "item:ingot")
                || com.mervyn.miforeman.goal.PlanSolver.makesOnly(electrolyse, "item:sulfur")) {
            helper.fail("Only a recipe whose sole output is the resource counts as making only it.");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testMiRecipesAreTheDefault(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation curvedPlate = ResourceLocation.parse("modern_industrialization:steel_curved_plate");
        var candidates = RecipeGraphTraverser.getCandidateRecipes(level, curvedPlate);
        if (candidates.stream().noneMatch(h -> h.id().getNamespace().equals("extended_industrialization"))) {
            helper.fail("Expected EI's bending machine to offer steel curved plates, got " + candidates);
            return;
        }
        if (!candidates.get(0).id().getNamespace().equals("modern_industrialization")) {
            helper.fail("MI's recipes must come first, got " + candidates.get(0).id());
            return;
        }

        var graph = RecipeGraphTraverser.computeRecipeGraphUncached(level,
                new ProductionGoal("mi_default", ProductionGoal.TargetType.ITEM, curvedPlate, 60.0),
                RecipeGraphTraverser.SolveMode.AUTO);
        ResourceLocation chosen = graph.node(curvedPlate).getSelectedAmbiguity();
        if (chosen == null || !chosen.getNamespace().equals("modern_industrialization")) {
            helper.fail("A steel curved plate plan should default to MI's recipe, got " + chosen);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testDeadLoopsAreFixed(GameTestHelper helper) {
        var level = helper.getLevel();
        var auto = RecipeGraphTraverser.SolveMode.AUTO;
        ResourceLocation ingot = ResourceLocation.parse("minecraft:iron_ingot");
        ResourceLocation nugget = ResourceLocation.parse("minecraft:iron_nugget");
        ResourceLocation plate = ResourceLocation.parse("modern_industrialization:iron_plate");

        var plateGraph = RecipeGraphTraverser.computeRecipeGraphUncached(level,
                new ProductionGoal("dead_loop_plate", ProductionGoal.TargetType.ITEM, plate, 60.0), auto);
        if (!plateGraph.autoImports().equals(Set.of(ingot)) || plateGraph.node(ingot) == null
                || plateGraph.node(ingot).getType() != com.mervyn.miforeman.goal.NodeType.RAW
                || !plateGraph.unsourcedResourceIds().isEmpty() || plateGraph.node(nugget) != null) {
            helper.fail("iron_plate should import iron ingots and drop the nugget loop, got imports " + plateGraph.autoImports()
                    + ", unsourced " + plateGraph.unsourcedResourceIds());
            return;
        }
        double compressors = plateGraph.nodes().values().stream()
                .filter(n -> n.getType() == com.mervyn.miforeman.goal.NodeType.MACHINE && n.getMachineType() != null
                        && n.getMachineType().getPath().equals("compressor"))
                .mapToDouble(com.mervyn.miforeman.goal.RecipeGraphNode::getMachineCount).sum();
        if (Math.abs(compressors - 5.0) > 1e-9) {
            helper.fail("iron_plate at 60/min should still need exactly 5 compressors, got " + compressors);
            return;
        }

        var forced = RecipeGraphTraverser.computeRecipeGraphUncached(level, new ProductionGoal("dead_loop_forced",
                ProductionGoal.TargetType.ITEM, plate, 60.0, Map.of(ingot, IRON_INGOT_PACKER), Optional.empty()), auto);
        if (!forced.unsourcedResourceIds().contains(ingot) || !forced.autoImports().isEmpty() || !forced.autoSelections().isEmpty()) {
            helper.fail("A manually picked looping recipe must stay, flagged: unsourced " + forced.unsourcedResourceIds()
                    + ", imports " + forced.autoImports() + ", picks " + forced.autoSelections());
            return;
        }

        var ingotGraph = RecipeGraphTraverser.computeRecipeGraphUncached(level,
                new ProductionGoal("dead_loop_target", ProductionGoal.TargetType.ITEM, ingot, 60.0), auto);
        if (ingotGraph.autoImports().contains(ingot) || ingotGraph.node(ingot).getType() != com.mervyn.miforeman.goal.NodeType.TARGET
                || !ingotGraph.autoImports().contains(nugget) || !ingotGraph.unsourcedResourceIds().isEmpty()) {
            helper.fail("An iron ingot goal should import nuggets, never its own target: imports " + ingotGraph.autoImports()
                    + ", unsourced " + ingotGraph.unsourcedResourceIds());
            return;
        }

        ResourceLocation cable = ResourceLocation.parse("modern_industrialization:annealed_copper_cable");
        var cableGraph = RecipeGraphTraverser.computeRecipeGraphUncached(level,
                new ProductionGoal("dead_loop_pick", ProductionGoal.TargetType.ITEM, cable, 60.0), auto);
        ResourceLocation picked = cableGraph.autoSelections().get(cable);
        if (picked == null || !picked.equals(ANNEALED_CABLE_PICK) || !cableGraph.unsourcedResourceIds().isEmpty()
                || cableGraph.node(picked) == null) {
            helper.fail("Expected annealed copper cable to be picked as " + ANNEALED_CABLE_PICK + ", got picks "
                    + cableGraph.autoSelections() + ", imports " + cableGraph.autoImports() + ", unsourced "
                    + cableGraph.unsourcedResourceIds());
            return;
        }
        helper.succeed();
    }

    private static final int FIX_PIVOT_CEILING = 5000;

    private static final ResourceLocation ANNEALED_CABLE_PICK =
            ResourceLocation.parse("modern_industrialization:materials/annealed_copper/assembler/cable_styrene_rubber");

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID, timeoutTicks = 400)
    public static void testDeadLoopFixIsDeterministic(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation quantum = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        long start = System.nanoTime();
        var first = RecipeGraphTraverser.computeRecipeGraphUncached(level,
                new ProductionGoal("dead_loop_determinism", ProductionGoal.TargetType.ITEM, quantum, 1.0), RecipeGraphTraverser.SolveMode.AUTO);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        var second = RecipeGraphTraverser.computeRecipeGraphUncached(level,
                new ProductionGoal("dead_loop_determinism", ProductionGoal.TargetType.ITEM, quantum, 1.0), RecipeGraphTraverser.SolveMode.AUTO);
        var faster = RecipeGraphTraverser.computeRecipeGraphUncached(level,
                new ProductionGoal("dead_loop_rate", ProductionGoal.TargetType.ITEM, quantum, 600.0), RecipeGraphTraverser.SolveMode.AUTO);
        int fixPivots = RecipeGraphTraverser.deadLoopFixPivots(level,
                new ProductionGoal("dead_loop_pivots", ProductionGoal.TargetType.ITEM, quantum, 1.0));
        MIForeman.LOGGER.info("quantum_upgrade plan with dead loops fixed: {}ms, {} pivots, {} picks, {} imports", elapsedMs,
                fixPivots, first.autoSelections().size(), first.autoImports().size());
        if (fixPivots <= 0 || fixPivots > FIX_PIVOT_CEILING) {
            helper.fail("Fixing quantum_upgrade's dead loops took " + fixPivots + " pivots, past the " + FIX_PIVOT_CEILING
                    + " ceiling: the fix is doing far more passes or solving far bigger models than it did.");
            return;
        }
        if (!first.unsourcedResourceIds().isEmpty()) {
            helper.fail("quantum_upgrade still has loops with no outside input: " + first.unsourcedResourceIds());
            return;
        }
        for (var other : List.of(second, faster)) {
            if (!other.autoSelections().equals(first.autoSelections()) || !other.autoImports().equals(first.autoImports())
                    || !other.nodes().keySet().equals(first.nodes().keySet())) {
                helper.fail("Dead-loop fixes changed between identical or rescaled solves: " + first.autoSelections() + " / "
                        + first.autoImports() + " vs " + other.autoSelections() + " / " + other.autoImports());
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testLayoutSurvivesRemovedNodeIds(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation plate = ResourceLocation.parse("modern_industrialization:iron_plate");
        var looped = RecipeGraphTraverser.computeRecipeGraphUncached(level, new ProductionGoal("layout_old",
                ProductionGoal.TargetType.ITEM, plate, 60.0, Map.of(ResourceLocation.parse("minecraft:iron_ingot"), IRON_INGOT_PACKER),
                Optional.empty()), RecipeGraphTraverser.SolveMode.AUTO);
        var fixed = RecipeGraphTraverser.computeRecipeGraphUncached(level,
                new ProductionGoal("layout_new", ProductionGoal.TargetType.ITEM, plate, 60.0), RecipeGraphTraverser.SolveMode.AUTO);
        Set<ResourceLocation> removed = new java.util.HashSet<>(looped.nodes().keySet());
        removed.removeAll(fixed.nodes().keySet());
        if (removed.isEmpty()) {
            helper.fail("Expected the dead-loop fix to remove nodes from iron_plate's graph.");
            return;
        }
        Map<ResourceLocation, com.mervyn.miforeman.goal.NodePosition> saved = new java.util.HashMap<>();
        int y = 0;
        for (var id : looped.nodes().keySet())
            saved.put(id, new com.mervyn.miforeman.goal.NodePosition(0, y += 40));
        Set<ResourceLocation> members = new java.util.HashSet<>(removed);
        members.add(plate);
        var group = new com.mervyn.miforeman.goal.NodeGroup(java.util.UUID.randomUUID(), members);
        var arranged = com.mervyn.miforeman.goal.GraphLayoutEngine.arrange(fixed, List.of(group), saved, false, 96, 26, 44);
        if (!arranged.keySet().containsAll(fixed.nodes().keySet())) {
            helper.fail("Every node of the fixed graph should be placed, missing: " + fixed.nodes().keySet().stream()
                    .filter(id -> !arranged.containsKey(id)).toList());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testMachineCountUsesPerMinuteRates(GameTestHelper helper) {
        var level = helper.getLevel();
        RecipeGraphTraverser.clearGraphCache();
        ResourceLocation plate = ResourceLocation.parse("modern_industrialization:iron_plate");
        var graph = RecipeGraphTraverser.computeRecipeGraph(level,
                new ProductionGoal("per_minute_units", ProductionGoal.TargetType.ITEM, plate, 60.0));
        var machine = graph.nodes().values().stream()
                .filter(n -> n.getType() == com.mervyn.miforeman.goal.NodeType.MACHINE && plate.equals(n.getAmbiguityOwnerId()))
                .findFirst().orElse(null);
        if (machine == null || machine.getRecipe() == null) {
            helper.fail("Expected a machine node making iron plates.");
            return;
        }
        var recipe = machine.getRecipe();
        double perRun = 0;
        for (var out : recipe.itemOutputs) {
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(out.variant().getItem()).equals(plate))
                perRun = out.amount() * out.probability();
        }
        // 60 plates a minute is 60 / perRun runs a minute, and one machine does 1200 / duration of them.
        double expected = (60.0 / perRun) * recipe.duration / 1200.0;
        if (Math.abs(machine.getMachineCount() - expected) > 1e-9) {
            helper.fail("Iron plates at 60/min need " + expected + " machines (" + recipe.duration + "-tick recipe), plan says "
                    + machine.getMachineCount() + ". Rates are per minute, not per second.");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testCollectByproductRates(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal goal = new ProductionGoal("byproduct_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);
        var graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);

        var byproductRates = RecipeGraphTraverser.collectByproductRates(graph);

        for (var entry : byproductRates.entrySet()) {
            ResourceLocation id = entry.getKey();
            double rate = entry.getValue();
            if (rate <= 0.0) {
                helper.fail("Expected every collectByproductRates entry to have a positive rate, but " + id
                        + " has: " + rate);
                return;
            }
            if (Math.abs(graph.surplusRates().getOrDefault(id, 0.0) - rate) > 1e-9) {
                helper.fail("collectByproductRates reported " + rate + " for " + id + " but the graph's surplus is "
                        + graph.surplusRates().get(id));
                return;
            }
        }

        if (byproductRates.isEmpty()) {
            helper.fail("Expected quantum_upgrade's graph to have at least one genuine byproduct "
                    + "(a real recipe output the plan didn't itself demand) to build this test on.");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testRecipeResourceIdsCollectsInputsAndOutputs(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal goal = new ProductionGoal("recipe_resource_ids_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);
        var graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);

        var machineNode = graph.nodes().values().stream()
                .filter(n -> n.getType() == com.mervyn.miforeman.goal.NodeType.MACHINE && n.getRecipe() != null)
                .findFirst().orElse(null);
        if (machineNode == null) {
            helper.fail("Expected quantum_upgrade's graph to contain at least one MACHINE node with a recipe.");
            return;
        }

        var ids = RecipeGraphTraverser.recipeResourceIds(machineNode.getRecipe());
        if (ids.isEmpty()) {
            helper.fail("Expected recipeResourceIds(...) to return at least one resource id for a real recipe, but got an empty set.");
            return;
        }
        for (var edge : machineNode.getInputs()) {
            if (!ids.contains(edge.from())) {
                helper.fail("Expected recipeResourceIds(...) to include input resource " + edge.from()
                        + " that the graph itself has as an input edge.");
                return;
            }
        }
        for (var edge : machineNode.getOutputs()) {
            if (!ids.contains(edge.to())) {
                helper.fail("Expected recipeResourceIds(...) to include output resource " + edge.to()
                        + " that the graph itself has as an output edge.");
                return;
            }
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testDisplayFormatFormatRate(GameTestHelper helper) {
        String perMinute = com.mervyn.miforeman.client.DisplayFormat.formatRate(2.0, false);
        if (!perMinute.equals("2.0/m")) {
            helper.fail("Expected formatRate(2.0, false) to be \"2.0/m\", but got: " + perMinute);
            return;
        }
        String perHour = com.mervyn.miforeman.client.DisplayFormat.formatRate(2.0, true);
        if (!perHour.equals("120.0/h")) {
            helper.fail("Expected formatRate(2.0, true) to be \"120.0/h\" (rate scaled by 60), but got: " + perHour);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testCloseSyncGoalPreservesLastSynced(GameTestHelper helper) {
        ResourceLocation targetId = ResourceLocation.parse("minecraft:iron_ingot");
        ProductionGoal synced = new ProductionGoal("synced_goal", ProductionGoal.TargetType.ITEM, targetId, 5.0,
                Map.of(), java.util.Optional.empty(), false, 0.8,
                List.of(GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, new BlockPos(1, 2, 3))),
                com.mervyn.miforeman.goal.GraphLayoutState.EMPTY,
                com.mervyn.miforeman.goal.MachineLinkHistory.EMPTY, List.of());

        if (com.mervyn.miforeman.goal.ClipboardCloseSync.computeCloseSyncGoal(
                synced, synced.uiState(), synced.graphLayout()).isPresent()) {
            helper.fail("Expected no goal to persist when neither ui state nor layout changed.");
            return;
        }

        // Only the UI state changed. The persisted goal keeps synced's other fields with the new UI state on top.
        var newUiState = new com.mervyn.miforeman.goal.ClipboardUiState(1, 9.0, 9.0, 2.0f, false, false, true, true);
        var afterUiChange = com.mervyn.miforeman.goal.ClipboardCloseSync.computeCloseSyncGoal(
                synced, newUiState, synced.graphLayout());
        if (afterUiChange.isEmpty()) {
            helper.fail("Expected a goal to persist when ui state changed.");
            return;
        }
        if (!afterUiChange.get().linkedMachines().equals(synced.linkedMachines())) {
            helper.fail("computeCloseSyncGoal dropped linkedMachines that were already synced. "
                    + "Expected: " + synced.linkedMachines() + ", got: " + afterUiChange.get().linkedMachines());
            return;
        }
        if (!afterUiChange.get().uiState().equals(newUiState)) {
            helper.fail("computeCloseSyncGoal did not apply the new ui state snapshot.");
            return;
        }

        if (com.mervyn.miforeman.goal.ClipboardCloseSync.computeCloseSyncGoal(
                null, newUiState, synced.graphLayout()).isPresent()) {
            helper.fail("Expected no goal to persist when base (lastSyncedGoal) is null.");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testDimensionKeyedTrackers(GameTestHelper helper) {
        var level = helper.getLevel();
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        if (nether == null) {
            helper.fail("Nether level missing from test server");
            return;
        }

        // Trackers are global static state.
        ServerMonitoringManager.TRACKERS.clear();

        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        GlobalPos overworldKey = ServerMonitoringManager.key(level, pos);
        GlobalPos netherKey = ServerMonitoringManager.key(nether, pos);

        if (overworldKey.equals(netherKey)) {
            helper.fail("Same BlockPos in different dimensions produced identical tracker keys");
            return;
        }

        ServerMonitoringManager.TRACKERS.put(overworldKey, new ServerMonitoringManager.MachineTracker(pos));
        ServerMonitoringManager.TRACKERS.put(netherKey, new ServerMonitoringManager.MachineTracker(pos));
        if (ServerMonitoringManager.TRACKERS.size() != 2) {
            helper.fail("Expected 2 independent trackers for same coords in 2 dimensions, got: "
                    + ServerMonitoringManager.TRACKERS.size());
            return;
        }

        ServerMonitoringManager.pruneTrackers(java.util.Set.of());
        if (!ServerMonitoringManager.TRACKERS.isEmpty()) {
            helper.fail("pruneTrackers left " + ServerMonitoringManager.TRACKERS.size()
                    + " stale tracker(s) behind");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testPowerDemandMatchesRequirements(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal goal = new ProductionGoal("power_demand_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);

        ProductionGoal.FactoryPlan plan = RecipeGraphTraverser.computePlan(level, goal);

        if (plan.machines().isEmpty()) {
            helper.fail("Expected quantum_upgrade plan to have at least one machine requirement.");
            return;
        }

        long summedFromRequirements = plan.machines().stream()
                .mapToLong(ProductionGoal.MachineRequirement::totalEuPerTick)
                .sum();

        if (plan.totalPowerDemandEu() != summedFromRequirements) {
            helper.fail("FactoryPlan.totalPowerDemandEu() (" + plan.totalPowerDemandEu()
                    + ") does not match sum of machines().totalEuPerTick() (" + summedFromRequirements + ")");
            return;
        }

        // baseEuPerTick reflects only the last recipe recorded for a machine type, while count and totalEuPerTick sum every recipe using it.
        for (var req : plan.machines()) {
            if (req.totalEuPerTick() <= 0) {
                helper.fail("Machine " + req.machineId() + " has non-positive totalEuPerTick: " + req.totalEuPerTick());
                return;
            }
            if (req.count() <= 0) {
                helper.fail("Machine " + req.machineId() + " has non-positive count: " + req.count());
                return;
            }
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testFluidTargetGoalTraversal(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:sulfuric_acid");
        ProductionGoal goal = new ProductionGoal("fluid_target_test", ProductionGoal.TargetType.FLUID, targetId, 10.0);

        ProductionGoal.FactoryPlan plan = RecipeGraphTraverser.computePlan(level, goal);
        if (plan.machines().isEmpty()) {
            helper.fail("Expected non-empty machine requirements for FLUID target " + targetId);
            return;
        }
        com.mervyn.miforeman.goal.RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);

        ResourceLocation sulfurDust = ResourceLocation.parse("modern_industrialization:sulfur_dust");
        if (!graph.autoImports().equals(Set.of(sulfurDust)) || !graph.unsourcedResourceIds().isEmpty()
                || plan.rawInputs().stream().noneMatch(flow -> flow.resourceId().equals(sulfurDust) && flow.rate() > 0)) {
            helper.fail("Expected the sulfuric acid loop to be cut by importing sulfur dust, got imports "
                    + graph.autoImports() + ", unsourced " + graph.unsourcedResourceIds());
            return;
        }
        if (graph.nodes().isEmpty()) {
            helper.fail("Expected non-empty node set in the recipe graph for FLUID target " + targetId);
            return;
        }
        if (graph.edges().isEmpty()) {
            helper.fail("Expected non-empty edge set in the recipe graph for FLUID target " + targetId);
            return;
        }
        if (!graph.nodes().containsKey(targetId)) {
            helper.fail("Expected the recipe graph to contain a node for the FLUID target itself: " + targetId);
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGraphCacheHitAndInvalidation(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");

        RecipeGraphTraverser.clearGraphCache();

        ProductionGoal goal = new ProductionGoal("cache_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);

        // computeRecipeGraph returns a copy each call, so compare structure, not identity.
        var first = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        var second = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        if (!sameGraphContent(first, second)) {
            helper.fail("Expected two computeRecipeGraph calls for an unchanged goal query to return "
                    + "structurally identical content.");
            return;
        }

        RecipeGraphTraverser.clearGraphCache();
        var afterClear = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        if (!sameGraphContent(afterClear, first)) {
            helper.fail("Expected a fresh recompute after clearGraphCache() to still match the original "
                    + "content for an unchanged goal query (computation should be deterministic).");
            return;
        }

        var ambiguousNode = afterClear.nodes().values().stream()
                .filter(node -> node.getType() != com.mervyn.miforeman.goal.NodeType.MACHINE
                        && node.getAmbiguityOptions().size() > 1)
                .findFirst()
                .orElse(null);
        if (ambiguousNode == null) {
            helper.fail("Expected at least one ambiguous resource node in quantum_upgrade recipe graph.");
            return;
        }

        ResourceLocation altRecipe = ambiguousNode.getAmbiguityOptions().get(1);
        ProductionGoal altGoal = goal.withRecipeSelections(
                Map.of(ambiguousNode.getAmbiguityOwnerId(), altRecipe));

        var altGraph = RecipeGraphTraverser.computeRecipeGraph(level, altGoal);
        if (sameGraphContent(altGraph, afterClear)) {
            helper.fail("Expected a goal with different recipeSelections to produce structurally "
                    + "different content than the default-selections goal.");
            return;
        }

        var stillCachedDefault = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        if (!sameGraphContent(stillCachedDefault, afterClear)) {
            helper.fail("Populating the cache for altGoal's recipeSelections corrupted the existing "
                    + "entry's content for the default-selections goal.");
            return;
        }

        RecipeGraphTraverser.clearGraphCache();
        helper.succeed();
    }

    /** RecipeGraphNode has no value-based equals, so tests compare graphs by content. */
    private static boolean sameGraphContent(com.mervyn.miforeman.goal.RecipeGraph a, com.mervyn.miforeman.goal.RecipeGraph b) {
        if (!a.target().equals(b.target()) || a.targetRate() != b.targetRate()) {
            return false;
        }
        if (!a.nodes().keySet().equals(b.nodes().keySet())) {
            return false;
        }
        if (a.edges().size() != b.edges().size()) {
            return false;
        }
        var aPairs = a.edges().stream().map(e -> List.of(e.from(), e.to())).collect(java.util.stream.Collectors.toSet());
        var bPairs = b.edges().stream().map(e -> List.of(e.from(), e.to())).collect(java.util.stream.Collectors.toSet());
        if (!aPairs.equals(bPairs)) {
            return false;
        }
        return a.cyclicResourceIds().equals(b.cyclicResourceIds());
    }

    /** iron_ingot and iron_nugget form a real 2-cycle through MI's packer and unpacker. Update the snapshot if those recipes change. */
    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testRecipeGraphCyclicResourceIdsCaptured(GameTestHelper helper) {
        var level = helper.getLevel();
        RecipeGraphTraverser.clearGraphCache();

        ResourceLocation cyclicTarget = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal cyclicGoal = new ProductionGoal("cyclic_capture_test", ProductionGoal.TargetType.ITEM, cyclicTarget, 1.0);
        var cyclicGraph = RecipeGraphTraverser.computeRecipeGraph(level, cyclicGoal);

        if (cyclicGraph.cyclicResourceIds().isEmpty()) {
            helper.fail("Expected quantum_upgrade's recipe graph to contain at least one recycling-loop "
                    + "resource captured by DagWalk's back-edge recording, but cyclicResourceIds() was empty.");
            return;
        }

        ResourceLocation ironPlateTarget = ResourceLocation.parse("modern_industrialization:iron_plate");
        ProductionGoal ironPlateGoal = new ProductionGoal("iron_plate_cycle_snapshot_test", ProductionGoal.TargetType.ITEM, ironPlateTarget, 1.0,
                Map.of(ResourceLocation.parse("minecraft:iron_ingot"), IRON_INGOT_PACKER), Optional.empty());
        var ironPlateGraph = RecipeGraphTraverser.computeRecipeGraph(level, ironPlateGoal);

        var expectedIronCycle = Set.of(ResourceLocation.parse("minecraft:iron_ingot"), ResourceLocation.parse("minecraft:iron_nugget"));
        if (!ironPlateGraph.cyclicResourceIds().equals(expectedIronCycle)) {
            helper.fail("Expected iron_plate's recipe graph cyclicResourceIds() to be exactly "
                    + expectedIronCycle + " (the ingot/nugget packer-unpacker loop), but got: "
                    + ironPlateGraph.cyclicResourceIds() + ". If MI's packer/unpacker recipes changed "
                    + "intentionally, update this snapshot.");
            return;
        }

        RecipeGraphTraverser.clearGraphCache();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testPeekCyclicResourceIdsCacheOnly(GameTestHelper helper) {
        var level = helper.getLevel();
        RecipeGraphTraverser.clearGraphCache();

        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal goal = new ProductionGoal("peek_cache_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);

        if (!RecipeGraphTraverser.peekCyclicResourceIds(goal).isEmpty()) {
            helper.fail("Expected peekCyclicResourceIds() to return empty before the graph has ever been "
                    + "computed for this goal. It should only read the cache.");
            return;
        }

        var graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        var peeked = RecipeGraphTraverser.peekCyclicResourceIds(goal);
        if (!peeked.equals(graph.cyclicResourceIds())) {
            helper.fail("Expected peekCyclicResourceIds() to match the freshly-computed graph's "
                    + "cyclicResourceIds() once cached. Computed: " + graph.cyclicResourceIds() + ", peeked: " + peeked);
            return;
        }

        RecipeGraphTraverser.clearGraphCache();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testClassifyLiveStatusDeadLoopReason(GameTestHelper helper) {
        var level = helper.getLevel();
        RecipeGraphTraverser.clearGraphCache();

        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal baseGoal = new ProductionGoal("classify_live_status_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);
        var graph = RecipeGraphTraverser.computeRecipeGraph(level, baseGoal);
        if (graph.cyclicResourceIds().isEmpty()) {
            helper.fail("Expected quantum_upgrade's graph to have at least one cyclic resource to build this test on.");
            return;
        }
        ResourceLocation cyclicResource = graph.cyclicResourceIds().iterator().next();
        ResourceLocation nonCyclicResource = ResourceLocation.parse("miforeman_test:not_cyclic_resource");

        // Same key as baseGoal, so peekCyclicResourceIds hits the graph cached above.
        ProductionGoal goal = baseGoal.withPlan(java.util.Optional.of(new ProductionGoal.FactoryPlan(
                List.of(), List.of(),
                List.of(new ProductionGoal.MaterialFlow(ProductionGoal.TargetType.ITEM, cyclicResource, 10.0),
                        new ProductionGoal.MaterialFlow(ProductionGoal.TargetType.ITEM, nonCyclicResource, 10.0)),
                List.of())));

        var tracker = new ServerMonitoringManager.MachineTracker(new BlockPos(0, 0, 0));
        tracker.status = com.mervyn.miforeman.goal.MachineStatus.GREEN;
        tracker.failureReason = com.mervyn.miforeman.goal.FailureReason.NONE;

        var cyclicResult = ServerMonitoringManager.classifyLiveStatus(goal, tracker,
                Map.of(cyclicResource, 1.0), Map.of(cyclicResource, 2.0)); // 2.0 < 10.0 * threshold
        if (cyclicResult.status() != com.mervyn.miforeman.goal.MachineStatus.YELLOW) {
            helper.fail("Expected a shortfall on a cyclic resource to read YELLOW, but got: " + cyclicResult.status());
            return;
        }
        if (cyclicResult.reason() != com.mervyn.miforeman.goal.FailureReason.DEAD_LOOP) {
            helper.fail("Expected a shortfall on a cyclic resource to report DEAD_LOOP, but got: " + cyclicResult.reason());
            return;
        }

        var nonCyclicResult = ServerMonitoringManager.classifyLiveStatus(goal, tracker,
                Map.of(nonCyclicResource, 1.0), Map.of(nonCyclicResource, 2.0));
        if (nonCyclicResult.status() != com.mervyn.miforeman.goal.MachineStatus.YELLOW) {
            helper.fail("Expected a shortfall on a non-cyclic resource to still read YELLOW, but got: "
                    + nonCyclicResult.status());
            return;
        }
        if (nonCyclicResult.reason() != com.mervyn.miforeman.goal.FailureReason.NONE) {
            helper.fail("Expected a shortfall on a non-cyclic resource to report NONE (not DEAD_LOOP), but got: "
                    + nonCyclicResult.reason());
            return;
        }

        var okResult = ServerMonitoringManager.classifyLiveStatus(goal, tracker,
                Map.of(cyclicResource, 1.0), Map.of(cyclicResource, 20.0)); // 20.0 >= 10.0 * threshold
        if (okResult.status() != com.mervyn.miforeman.goal.MachineStatus.GREEN
                || okResult.reason() != com.mervyn.miforeman.goal.FailureReason.NONE) {
            helper.fail("Expected rates meeting the expected threshold to leave status/reason unchanged "
                    + "(GREEN/NONE), but got: " + okResult.status() + "/" + okResult.reason());
            return;
        }

        tracker.disposalRatio = 0.9;
        var throttledResult = ServerMonitoringManager.classifyLiveStatus(goal, tracker,
                Map.of(nonCyclicResource, 1.0), Map.of(nonCyclicResource, 2.0));
        if (throttledResult.status() != com.mervyn.miforeman.goal.MachineStatus.YELLOW
                || throttledResult.reason() != com.mervyn.miforeman.goal.FailureReason.DISPOSAL_THROTTLED) {
            helper.fail("Expected a non-cyclic shortfall with a near-full output to report YELLOW/DISPOSAL_THROTTLED, "
                    + "but got: " + throttledResult.status() + "/" + throttledResult.reason());
            return;
        }

        // DEAD_LOOP must win whatever order the rates map iterates in.
        tracker.disposalRatio = 0.0;
        var bothUnderperformingResult = ServerMonitoringManager.classifyLiveStatus(goal, tracker,
                Map.of(cyclicResource, 1.0, nonCyclicResource, 1.0),
                Map.of(cyclicResource, 2.0, nonCyclicResource, 2.0));
        if (bothUnderperformingResult.reason() != com.mervyn.miforeman.goal.FailureReason.DEAD_LOOP) {
            helper.fail("Expected a simultaneous cyclic+non-cyclic shortfall to always report DEAD_LOOP, "
                    + "but got: " + bothUnderperformingResult.reason());
            return;
        }

        RecipeGraphTraverser.clearGraphCache();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testUnionCyclicResourceIdsAcrossGoals(GameTestHelper helper) {
        var level = helper.getLevel();
        RecipeGraphTraverser.clearGraphCache();

        ResourceLocation cyclicTargetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal cyclicGoal = new ProductionGoal("union_cyclic_test", ProductionGoal.TargetType.ITEM, cyclicTargetId, 1.0);
        var cyclicGraph = RecipeGraphTraverser.computeRecipeGraph(level, cyclicGoal);
        if (cyclicGraph.cyclicResourceIds().isEmpty()) {
            helper.fail("Expected quantum_upgrade's graph to have at least one cyclic resource to build this test on.");
            return;
        }
        ResourceLocation cyclicResource = cyclicGraph.cyclicResourceIds().iterator().next();

        ResourceLocation nonCyclicTargetId = ResourceLocation.parse("modern_industrialization:iron_dust");
        ProductionGoal nonCyclicGoal = new ProductionGoal("union_noncyclic_test", ProductionGoal.TargetType.ITEM, nonCyclicTargetId, 1.0);
        var nonCyclicGraph = RecipeGraphTraverser.computeRecipeGraph(level, nonCyclicGoal);
        if (nonCyclicGraph.cyclicResourceIds().contains(cyclicResource)) {
            helper.fail("Test assumption broken: expected iron_dust's graph to NOT consider " + cyclicResource + " cyclic.");
            return;
        }

        var unionForward = ServerMonitoringManager.unionCyclicResourceIds(List.of(nonCyclicGoal, cyclicGoal));
        var unionBackward = ServerMonitoringManager.unionCyclicResourceIds(List.of(cyclicGoal, nonCyclicGoal));
        if (!unionForward.contains(cyclicResource) || !unionBackward.contains(cyclicResource)) {
            helper.fail("Expected the union of two linking goals' cyclic-resource sets to contain " + cyclicResource
                    + " regardless of list order, but got forward=" + unionForward + " backward=" + unionBackward);
            return;
        }

        var nonCyclicOnly = ServerMonitoringManager.unionCyclicResourceIds(List.of(nonCyclicGoal));
        if (nonCyclicOnly.contains(cyclicResource)) {
            helper.fail("Expected a single non-cyclic-linking goal to not contribute " + cyclicResource + ", but got: " + nonCyclicOnly);
            return;
        }

        RecipeGraphTraverser.clearGraphCache();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testRecipeLiveSummaryWorstStatusAndRunningCount(GameTestHelper helper) {
        ResourceLocation shared = ResourceLocation.parse("modern_industrialization:materials/iron/macerator/ore");
        ResourceLocation solo = ResourceLocation.parse("modern_industrialization:materials/copper/macerator/ore");
        var machineId = ResourceLocation.parse("modern_industrialization:electric_macerator");
        java.util.function.BiFunction<com.mervyn.miforeman.goal.MachineStatus, java.util.Optional<ResourceLocation>,
                com.mervyn.miforeman.network.LiveMonitoringPayload.MachineStatusData> machine = (status, recipe) ->
                new com.mervyn.miforeman.network.LiveMonitoringPayload.MachineStatusData(
                        GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), status,
                        com.mervyn.miforeman.goal.FailureReason.NONE, 0.0, 0.0, machineId, recipe,
                        false, java.util.Optional.empty());

        // The starved machine goes first, so a last-put-wins map would hide it behind the GREEN one.
        var summaries = com.mervyn.miforeman.goal.RecipeLiveSummary.byRecipe(List.of(
                machine.apply(com.mervyn.miforeman.goal.MachineStatus.RED, java.util.Optional.of(shared)),
                machine.apply(com.mervyn.miforeman.goal.MachineStatus.GREEN, java.util.Optional.of(shared)),
                machine.apply(com.mervyn.miforeman.goal.MachineStatus.YELLOW, java.util.Optional.of(shared)),
                machine.apply(com.mervyn.miforeman.goal.MachineStatus.GREEN, java.util.Optional.of(shared)),
                machine.apply(com.mervyn.miforeman.goal.MachineStatus.ORANGE, java.util.Optional.of(solo)),
                machine.apply(com.mervyn.miforeman.goal.MachineStatus.GREEN, java.util.Optional.empty())));

        var sharedSummary = summaries.get(shared);
        if (sharedSummary == null || sharedSummary.worst() != com.mervyn.miforeman.goal.MachineStatus.RED) {
            helper.fail("Expected the shared recipe to report its worst machine (RED), got " + sharedSummary);
            return;
        }
        if (sharedSummary.running() != 3 || sharedSummary.total() != 4) {
            helper.fail("Expected 3/4 running on the shared recipe, got " + sharedSummary.running() + "/" + sharedSummary.total());
            return;
        }
        var soloSummary = summaries.get(solo);
        if (soloSummary == null || soloSummary.worst() != com.mervyn.miforeman.goal.MachineStatus.ORANGE
                || soloSummary.running() != 0 || soloSummary.total() != 1) {
            helper.fail("Expected the solo recipe to be ORANGE 0/1, got " + soloSummary);
            return;
        }
        if (summaries.size() != 2) {
            helper.fail("Expected exactly two recipe entries, got " + summaries.keySet());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testMachineRecipeHistoryRestoresPrunedTracker(GameTestHelper helper) {
        var history = new com.mervyn.miforeman.goal.MachineRecipeHistory();
        GlobalPos key = GlobalPos.of(Level.OVERWORLD, new BlockPos(3, 64, 7));
        ResourceLocation recipe = ResourceLocation.parse("modern_industrialization:materials/iron/compressor/main");

        // Re-recording the same recipe (a machine running it every tick) must not dirty the save.
        history.record(key, recipe);
        history.setDirty(false);
        history.record(key, recipe);
        if (history.isDirty()) {
            helper.fail("Recording an unchanged recipe marked the history dirty.");
            return;
        }

        var registries = helper.getLevel().registryAccess();
        var reloaded = com.mervyn.miforeman.goal.MachineRecipeHistory.load(
                history.save(new net.minecraft.nbt.CompoundTag(), registries), registries);
        if (!recipe.equals(reloaded.get(key))) {
            helper.fail("Recipe history lost its entry across save/load: " + reloaded.get(key));
            return;
        }

        var tracker = new ServerMonitoringManager.MachineTracker(key.pos());
        ServerMonitoringManager.seedLastKnownRecipe(tracker, reloaded, key, id -> true);
        if (!recipe.equals(tracker.lastKnownRecipeId)) {
            helper.fail("A pruned tracker was not restored from recipe history: " + tracker.lastKnownRecipeId);
            return;
        }

        ResourceLocation live = ResourceLocation.parse("modern_industrialization:materials/copper/compressor/main");
        var running = new ServerMonitoringManager.MachineTracker(key.pos());
        running.lastKnownRecipeId = live;
        ServerMonitoringManager.seedLastKnownRecipe(running, reloaded, key, id -> true);
        if (!live.equals(running.lastKnownRecipeId)) {
            helper.fail("Seeding overwrote a tracker that already had a live recipe.");
            return;
        }

        var swapped = new ServerMonitoringManager.MachineTracker(key.pos());
        ServerMonitoringManager.seedLastKnownRecipe(swapped, reloaded, key, id -> false);
        if (swapped.lastKnownRecipeId != null || reloaded.get(key) != null) {
            helper.fail("An invalid saved recipe was used or kept: tracker=" + swapped.lastKnownRecipeId
                    + ", history=" + reloaded.get(key));
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testUpdateMachineRestoresAndForgetsRecipeHistory(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relativePos = new BlockPos(1, 1, 1);
        helper.setBlock(relativePos, net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("modern_industrialization:bronze_compressor")));
        GlobalPos key = GlobalPos.of(level.dimension(), helper.absolutePos(relativePos));

        var compressorRecipes = aztech.modern_industrialization.machines.init.MIMachineRecipeTypes.COMPRESSOR.getRecipesWithCache(level);
        var maceratorRecipes = aztech.modern_industrialization.machines.init.MIMachineRecipeTypes.MACERATOR.getRecipesWithCache(level);
        if (compressorRecipes.isEmpty() || maceratorRecipes.isEmpty()) {
            helper.fail("Expected MI to register compressor and macerator recipes.");
            return;
        }
        ResourceLocation compressorRecipe = compressorRecipes.iterator().next().id();
        ResourceLocation maceratorRecipe = maceratorRecipes.iterator().next().id();

        try {
            ServerMonitoringManager.TRACKERS.remove(key);
            var history = new com.mervyn.miforeman.goal.MachineRecipeHistory();
            history.record(key, compressorRecipe);
            ServerMonitoringManager.updateMachine(level, key, history, List.of());

            var tracker = ServerMonitoringManager.TRACKERS.get(key);
            if (tracker == null || !compressorRecipe.equals(ServerMonitoringManager.resolveDisplayRecipeId(tracker))) {
                helper.fail("An idle machine's saved recipe was not restored after its tracker was pruned: "
                        + (tracker == null ? "no tracker" : tracker.lastKnownRecipeId));
                return;
            }
            if (tracker.status != com.mervyn.miforeman.goal.MachineStatus.RED) {
                helper.fail("An empty, idle compressor should still report RED, got " + tracker.status);
                return;
            }

            ServerMonitoringManager.TRACKERS.remove(key);
            history.record(key, maceratorRecipe);
            ServerMonitoringManager.updateMachine(level, key, history, List.of());
            tracker = ServerMonitoringManager.TRACKERS.get(key);
            if (tracker.lastKnownRecipeId != null || history.get(key) != null) {
                helper.fail("A macerator recipe was accepted for a compressor: tracker=" + tracker.lastKnownRecipeId
                        + ", history=" + history.get(key));
                return;
            }

            history.record(key, compressorRecipe);
            helper.setBlock(relativePos, net.minecraft.world.level.block.Blocks.AIR);
            ServerMonitoringManager.updateMachine(level, key, history, List.of());
            if (history.get(key) != null) {
                helper.fail("A broken machine's recipe history was kept: " + history.get(key));
                return;
            }
        } finally {
            ServerMonitoringManager.TRACKERS.remove(key);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testScanPayloadsCarryRadius(GameTestHelper helper) {
        var level = helper.getLevel();
        ProductionGoal goal = new ProductionGoal("scan_payload_goal", ProductionGoal.TargetType.ITEM,
                ResourceLocation.parse("modern_industrialization:iron_plate"), 2.0);

        @SuppressWarnings("deprecation")
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
        var request = new com.mervyn.miforeman.network.ScanRequestPayload(goal, 7);
        com.mervyn.miforeman.network.ScanRequestPayload.STREAM_CODEC.encode(buf, request);
        var decodedRequest = com.mervyn.miforeman.network.ScanRequestPayload.STREAM_CODEC.decode(buf);
        if (decodedRequest.radiusChunks() != 7 || !decodedRequest.goal().equals(goal)) {
            helper.fail("ScanRequestPayload did not round-trip: radius=" + decodedRequest.radiusChunks());
            return;
        }

        var result = new com.mervyn.miforeman.network.ScanResultPayload(List.of(
                new com.mervyn.miforeman.network.ScanResultPayload.Candidate(
                        GlobalPos.of(Level.OVERWORLD, new BlockPos(10, 64, -3)),
                        ResourceLocation.parse("modern_industrialization:electric_compressor"),
                        ResourceLocation.parse("modern_industrialization:materials/iron/compressor/main"))), 5);
        com.mervyn.miforeman.network.ScanResultPayload.STREAM_CODEC.encode(buf, result);
        var decodedResult = com.mervyn.miforeman.network.ScanResultPayload.STREAM_CODEC.decode(buf);
        if (!decodedResult.equals(result)) {
            helper.fail("ScanResultPayload did not round-trip: " + decodedResult);
            return;
        }
        if (buf.readableBytes() != 0) {
            helper.fail("Scan payloads left " + buf.readableBytes() + " unread bytes, so encode and decode disagree.");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testScanStepperAndNewCandidates(GameTestHelper helper) {
        int dflt = com.mervyn.miforeman.goal.MachineScanner.DEFAULT_RADIUS;
        record Step(int pick, int lastRun, int localDefault, int delta, int expected, String why) {}
        List<Step> steps = List.of(
                new Step(dflt, 0, 4, 1, 5, "before any scan, steps from the local config default"),
                new Step(dflt, 6, 4, -1, 5, "once a default scan ran, steps from the radius it reported"),
                new Step(8, 6, 4, 1, 9, "an explicit pick steps from itself"),
                new Step(1, 0, 4, -1, 1, "never below one chunk"),
                new Step(16, 0, 4, 1, 16, "never above the hard maximum"));
        for (Step s : steps) {
            int actual = com.mervyn.miforeman.goal.MachineScanner.stepScanRadius(s.pick(), s.lastRun(), s.localDefault(), s.delta());
            if (actual != s.expected()) {
                helper.fail("stepScanRadius gave " + actual + ", expected " + s.expected() + ": " + s.why());
                return;
            }
        }

        if (com.mervyn.miforeman.goal.MachineScanner.pickAfterScan(12, 8) != 8) {
            helper.fail("A pick the server capped should show the capped radius afterwards.");
            return;
        }
        if (com.mervyn.miforeman.goal.MachineScanner.pickAfterScan(dflt, 4) != dflt) {
            helper.fail("A default pick must stay default after a scan, not turn into a fixed number.");
            return;
        }

        GlobalPos linked = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, 1));
        GlobalPos seenLastScan = GlobalPos.of(Level.OVERWORLD, new BlockPos(2, 64, 2));
        GlobalPos fresh = GlobalPos.of(Level.OVERWORLD, new BlockPos(3, 64, 3));
        var newlyFound = com.mervyn.miforeman.goal.MachineScanner.newlyFound(
                List.of(linked, seenLastScan, fresh), Set.of(linked, seenLastScan));
        if (!newlyFound.equals(List.of(fresh))) {
            helper.fail("Only the machine no earlier scan or link knew about should be pinged, got " + newlyFound);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testMachinePlacementClassifyAndCompatibleNodes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        RecipeGraphTraverser.clearGraphCache();
        var graph = RecipeGraphTraverser.computeRecipeGraph(level, new ProductionGoal("placement_test",
                ProductionGoal.TargetType.ITEM, ResourceLocation.parse("modern_industrialization:iron_plate"), 1.0));
        ResourceLocation compressorType = ResourceLocation.parse("modern_industrialization:compressor");
        var compatible = com.mervyn.miforeman.goal.MachinePlacement.compatibleNodes(graph.nodes().values(), compressorType);
        if (compatible.isEmpty()) {
            helper.fail("Expected iron_plate's graph to have at least one compressor node to place a compressor on.");
            return;
        }
        for (var node : compatible) {
            if (node.getType() != com.mervyn.miforeman.goal.NodeType.MACHINE || !compressorType.equals(node.getMachineType())) {
                helper.fail("compatibleNodes offered a node a compressor can't run: " + node.getId());
                return;
            }
        }
        if (!com.mervyn.miforeman.goal.MachinePlacement.compatibleNodes(graph.nodes().values(), null).isEmpty()) {
            helper.fail("A machine with an unknown recipe type must not be offered any node.");
            return;
        }

        ResourceLocation onGraph = compatible.get(0).getId();
        ResourceLocation offPlan = ResourceLocation.parse("modern_industrialization:materials/gold/compressor/main");
        Set<ResourceLocation> machineNodeIds = new java.util.HashSet<>();
        graph.nodes().forEach((id, node) -> {
            if (node.getType() == com.mervyn.miforeman.goal.NodeType.MACHINE) machineNodeIds.add(id);
        });
        var red = com.mervyn.miforeman.goal.MachineStatus.RED;
        var green = com.mervyn.miforeman.goal.MachineStatus.GREEN;
        record Case(java.util.Optional<ResourceLocation> recipe, boolean assigned, com.mervyn.miforeman.goal.MachineStatus status,
                    com.mervyn.miforeman.goal.MachinePlacement.Kind expected) {}
        for (Case c : List.of(
                new Case(java.util.Optional.empty(), false, red, com.mervyn.miforeman.goal.MachinePlacement.Kind.UNPLACED),
                new Case(java.util.Optional.of(onGraph), true, red, com.mervyn.miforeman.goal.MachinePlacement.Kind.ASSIGNED),
                new Case(java.util.Optional.of(onGraph), false, red, com.mervyn.miforeman.goal.MachinePlacement.Kind.ON_GRAPH),
                new Case(java.util.Optional.of(offPlan), false, green, com.mervyn.miforeman.goal.MachinePlacement.Kind.OFF_PLAN),
                new Case(java.util.Optional.of(offPlan), false, red, com.mervyn.miforeman.goal.MachinePlacement.Kind.UNPLACED))) {
            var machine = new com.mervyn.miforeman.network.LiveMonitoringPayload.MachineStatusData(
                    GlobalPos.of(Level.OVERWORLD, BlockPos.ZERO), c.status(),
                    com.mervyn.miforeman.goal.FailureReason.STARVED, 0.0, 0.0,
                    ResourceLocation.parse("modern_industrialization:electric_compressor"), c.recipe(), c.assigned(),
                    java.util.Optional.of(compressorType));
            var kind = com.mervyn.miforeman.goal.MachinePlacement.classify(machine, machineNodeIds);
            if (kind != c.expected()) {
                helper.fail("classify(recipe=" + c.recipe() + ", assigned=" + c.assigned() + ", status=" + c.status()
                        + ") = " + kind + ", expected " + c.expected());
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testRecipeTypeIdOfRealMachineMatchesGraphNodes(GameTestHelper helper) {
        // The placement filter depends on the type id read off a placed machine matching the id the graph stamps on its nodes.
        ServerLevel level = helper.getLevel();
        BlockPos relativePos = new BlockPos(1, 1, 1);
        helper.setBlock(relativePos, net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("modern_industrialization:bronze_compressor")));
        if (!(helper.getBlockEntity(relativePos) instanceof MachineBlockEntity machine)) {
            helper.fail("Placed block is not a MachineBlockEntity!");
            return;
        }
        var typeId = ServerMonitoringManager.recipeTypeIdOf(machine);
        if (typeId.isEmpty()) {
            helper.fail("Could not read a recipe type off a bronze compressor.");
            return;
        }
        RecipeGraphTraverser.clearGraphCache();
        var graph = RecipeGraphTraverser.computeRecipeGraph(level, new ProductionGoal("type_match_test",
                ProductionGoal.TargetType.ITEM, ResourceLocation.parse("modern_industrialization:iron_plate"), 1.0));
        if (com.mervyn.miforeman.goal.MachinePlacement.compatibleNodes(graph.nodes().values(), typeId.get()).isEmpty()) {
            helper.fail("A real compressor's type id " + typeId.get() + " matched no compressor node in iron_plate's graph.");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testResolveDisplayRecipeAssignmentPrecedence(GameTestHelper helper) {
        ResourceLocation assignment = ResourceLocation.parse("modern_industrialization:materials/iron/compressor/main");
        ResourceLocation real = ResourceLocation.parse("modern_industrialization:materials/copper/compressor/main");

        var never = new ServerMonitoringManager.MachineTracker(BlockPos.ZERO);
        var placed = ServerMonitoringManager.resolveDisplayRecipe(never, assignment, null);
        if (!assignment.equals(placed.recipeId()) || !placed.assigned()) {
            helper.fail("A never-run machine should show on its assigned node, flagged as assigned: " + placed);
            return;
        }
        var nothing = ServerMonitoringManager.resolveDisplayRecipe(never, null, null);
        if (nothing.recipeId() != null || nothing.assigned()) {
            helper.fail("A never-run, unplaced machine should show nowhere: " + nothing);
            return;
        }
        var ran = new ServerMonitoringManager.MachineTracker(BlockPos.ZERO);
        ServerMonitoringManager.recordActiveRecipe(ran, real);
        var afterCraft = ServerMonitoringManager.resolveDisplayRecipe(ran, assignment, Set.of(real, assignment));
        if (!real.equals(afterCraft.recipeId()) || afterCraft.assigned()) {
            helper.fail("A machine's own recipe must win over its assignment: " + afterCraft);
            return;
        }
        ran.lastRecipeId = null;
        var idleOnPlan = ServerMonitoringManager.resolveDisplayRecipe(ran, assignment, Set.of(real, assignment));
        if (!real.equals(idleOnPlan.recipeId()) || idleOnPlan.assigned()) {
            helper.fail("Idle history of an on-plan recipe must still beat a placement: " + idleOnPlan);
            return;
        }
        var idleOffPlan = ServerMonitoringManager.resolveDisplayRecipe(ran, assignment, Set.of(assignment));
        if (!assignment.equals(idleOffPlan.recipeId()) || !idleOffPlan.assigned()) {
            helper.fail("A placement should win over off-plan history on an idle machine: " + idleOffPlan);
            return;
        }
        var noGraph = ServerMonitoringManager.resolveDisplayRecipe(ran, assignment, null);
        if (!real.equals(noGraph.recipeId())) {
            helper.fail("With no cached graph, history should win: " + noGraph);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testMachineAssignmentsPersistAndFollowLinks(GameTestHelper helper) {
        var level = helper.getLevel();
        GlobalPos kept = GlobalPos.of(Level.OVERWORLD, new BlockPos(1, 64, 1));
        GlobalPos dropped = GlobalPos.of(Level.NETHER, new BlockPos(2, 70, 2));
        ResourceLocation recipe = ResourceLocation.parse("modern_industrialization:materials/iron/compressor/main");

        ProductionGoal goal = new ProductionGoal("assign_test", ProductionGoal.TargetType.ITEM,
                ResourceLocation.parse("modern_industrialization:iron_plate"), 1.0)
                .withLinkedMachines(List.of(kept, dropped), com.mervyn.miforeman.goal.MachineLinkHistory.EMPTY)
                .withMachineAssignment(kept, recipe)
                .withMachineAssignment(dropped, recipe);

        var nbt = ProductionGoal.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, goal).getOrThrow();
        var fromNbt = ProductionGoal.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, nbt).getOrThrow();
        if (!goal.machineAssignments().equals(fromNbt.machineAssignments())) {
            helper.fail("Assignments lost in the NBT codec: " + fromNbt.machineAssignments());
            return;
        }
        @SuppressWarnings("deprecation")
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
        ProductionGoal.STREAM_CODEC.encode(buf, goal);
        var fromStream = ProductionGoal.STREAM_CODEC.decode(buf);
        if (!goal.machineAssignments().equals(fromStream.machineAssignments()) || buf.readableBytes() != 0) {
            helper.fail("Assignments lost in the stream codec: " + fromStream.machineAssignments());
            return;
        }

        var legacy = ((net.minecraft.nbt.CompoundTag) nbt).copy();
        legacy.remove("machine_assignments");
        var fromLegacy = ProductionGoal.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, legacy).getOrThrow();
        if (!fromLegacy.machineAssignments().isEmpty()) {
            helper.fail("An old clipboard should load with no assignments, got " + fromLegacy.machineAssignments());
            return;
        }

        var unlinked = goal.withLinkedMachines(List.of(kept), goal.machineLinkHistory());
        if (!unlinked.machineAssignments().equals(Map.of(kept, recipe))) {
            helper.fail("Unlinking should drop only that machine's assignment, got " + unlinked.machineAssignments());
            return;
        }
        if (!unlinked.withMachineAssignment(kept, null).machineAssignments().isEmpty()) {
            helper.fail("Unplacing a machine should remove its assignment.");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testScanRadiusOverrideClampAndLinkValidation(GameTestHelper helper) {
        int dflt = com.mervyn.miforeman.goal.MachineScanner.DEFAULT_RADIUS;
        record Case(int requested, int defaultRadius, int maxRadius, int expected, String why) {}
        List<Case> cases = List.of(
                new Case(dflt, 4, 16, 4, "no pick uses the configured default"),
                new Case(8, 4, 16, 8, "a pick inside the cap is honoured"),
                new Case(12, 4, 8, 8, "a pick above the cap is clamped to it"),
                new Case(dflt, 4, 2, 2, "an admin cap below the default also caps the default scan"),
                new Case(-3, 4, 16, 1, "a malformed negative pick still scans at least one chunk"));
        for (Case c : cases) {
            int actual = com.mervyn.miforeman.goal.MachineScanner.effectiveScanRadius(c.requested(), c.defaultRadius(), c.maxRadius());
            if (actual != c.expected()) {
                helper.fail("effectiveScanRadius(" + c.requested() + ", " + c.defaultRadius() + ", " + c.maxRadius()
                        + ") = " + actual + ", expected " + c.expected() + ": " + c.why());
                return;
            }
        }

        // Validation must use the widest scan radius allowed, or links found by an 8-chunk scan get rejected.
        int validation = com.mervyn.miforeman.goal.MachineScanner.linkValidationRadius(4, 16);
        BlockPos player = BlockPos.ZERO;
        BlockPos eightChunksAway = new BlockPos(8 * 16, 64, 0);
        if (!com.mervyn.miforeman.goal.MachineScanner.isWithinScanRadius(player, eightChunksAway, validation)) {
            helper.fail("A machine 8 chunks away (inside the 16-chunk cap) failed link validation at radius " + validation);
            return;
        }
        if (com.mervyn.miforeman.goal.MachineScanner.linkValidationRadius(4, 2) != 4) {
            helper.fail("Link validation must never be tighter than the default scan radius.");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testResolveDisplayRecipeIdFallsBackToLastKnown(GameTestHelper helper) {
        var tracker = new ServerMonitoringManager.MachineTracker(new BlockPos(0, 0, 0));
        ResourceLocation active = ResourceLocation.parse("modern_industrialization:materials/iron/compressor/main");
        ResourceLocation saturated = ResourceLocation.parse("modern_industrialization:materials/copper/compressor/main");
        ResourceLocation lastKnown = ResourceLocation.parse("modern_industrialization:materials/gold/compressor/main");

        if (ServerMonitoringManager.resolveDisplayRecipeId(tracker) != null) {
            helper.fail("Expected a never-run tracker to resolve to a null display recipe id.");
            return;
        }

        tracker.lastKnownRecipeId = lastKnown;
        if (!lastKnown.equals(ServerMonitoringManager.resolveDisplayRecipeId(tracker))) {
            helper.fail("Expected a RED tracker with no active/saturated recipe to fall back to lastKnownRecipeId="
                    + lastKnown + ", but got: " + ServerMonitoringManager.resolveDisplayRecipeId(tracker));
            return;
        }

        tracker.saturatedRecipeId = saturated;
        if (!saturated.equals(ServerMonitoringManager.resolveDisplayRecipeId(tracker))) {
            helper.fail("Expected saturatedRecipeId to take priority over lastKnownRecipeId, but got: "
                    + ServerMonitoringManager.resolveDisplayRecipeId(tracker));
            return;
        }

        tracker.lastRecipeId = active;
        if (!active.equals(ServerMonitoringManager.resolveDisplayRecipeId(tracker))) {
            helper.fail("Expected lastRecipeId to take top priority, but got: "
                    + ServerMonitoringManager.resolveDisplayRecipeId(tracker));
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testRecordActiveRecipeNeverGoesStale(GameTestHelper helper) {
        var tracker = new ServerMonitoringManager.MachineTracker(new BlockPos(0, 0, 0));
        ResourceLocation cyclicRecipe = ResourceLocation.parse("modern_industrialization:materials/iron/compressor/main");
        ResourceLocation laterNonCyclicRecipe = ResourceLocation.parse("modern_industrialization:materials/gold/compressor/main");

        ServerMonitoringManager.recordActiveRecipe(tracker, cyclicRecipe);
        if (!cyclicRecipe.equals(tracker.lastKnownRecipeId)) {
            helper.fail("Expected lastKnownRecipeId to reflect the just-run recipe, but got: " + tracker.lastKnownRecipeId);
            return;
        }

        // A stale lastKnownRecipeId would misclassify a later unrelated starve as a dead loop.
        ServerMonitoringManager.recordActiveRecipe(tracker, laterNonCyclicRecipe);
        if (!laterNonCyclicRecipe.equals(tracker.lastKnownRecipeId)) {
            helper.fail("Expected lastKnownRecipeId to update to the latest active recipe " + laterNonCyclicRecipe
                    + " and not remain stale on " + cyclicRecipe + ", but got: " + tracker.lastKnownRecipeId);
            return;
        }

        if (!laterNonCyclicRecipe.equals(tracker.lastRecipeId)) {
            helper.fail("Expected lastRecipeId to also update to the latest active recipe, but got: " + tracker.lastRecipeId);
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testLiveMonitoringPayloadStreamCodecParity(GameTestHelper helper) {
        var level = helper.getLevel();

        var payload = new com.mervyn.miforeman.network.LiveMonitoringPayload(List.of(
                new com.mervyn.miforeman.network.LiveMonitoringPayload.MachineStatusData(
                        GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, new BlockPos(1, 2, 3)),
                        com.mervyn.miforeman.goal.MachineStatus.RED,
                        com.mervyn.miforeman.goal.FailureReason.DEAD_LOOP,
                        12.5,
                        0.0,
                        ResourceLocation.parse("modern_industrialization:bronze_compressor"),
                        java.util.Optional.of(ResourceLocation.parse("modern_industrialization:materials/iron/compressor/main")),
                        false,
                        java.util.Optional.of(ResourceLocation.parse("modern_industrialization:compressor"))),
                new com.mervyn.miforeman.network.LiveMonitoringPayload.MachineStatusData(
                        GlobalPos.of(net.minecraft.world.level.Level.NETHER, new BlockPos(4, 5, 6)),
                        com.mervyn.miforeman.goal.MachineStatus.ORANGE,
                        com.mervyn.miforeman.goal.FailureReason.CLOG_LOCK,
                        0.0,
                        1.0,
                        ResourceLocation.parse("modern_industrialization:electric_compressor"),
                        java.util.Optional.empty(),
                        false,
                        java.util.Optional.empty()),
                new com.mervyn.miforeman.network.LiveMonitoringPayload.MachineStatusData(
                        GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, new BlockPos(7, 8, 9)),
                        com.mervyn.miforeman.goal.MachineStatus.YELLOW,
                        com.mervyn.miforeman.goal.FailureReason.DISPOSAL_THROTTLED,
                        99.9,
                        0.9,
                        ResourceLocation.parse("modern_industrialization:macerator"),
                        java.util.Optional.of(ResourceLocation.parse("modern_industrialization:materials/iron/macerator/main")),
                        true,
                        java.util.Optional.of(ResourceLocation.parse("modern_industrialization:macerator")))));

        @SuppressWarnings("deprecation")
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                level.registryAccess());
        com.mervyn.miforeman.network.LiveMonitoringPayload.STREAM_CODEC.encode(buf, payload);
        var decoded = com.mervyn.miforeman.network.LiveMonitoringPayload.STREAM_CODEC.decode(buf);

        if (!decoded.equals(payload)) {
            helper.fail("STREAM_CODEC round-trip does not match original LiveMonitoringPayload. A field was "
                    + "likely added to MachineStatusData without updating STREAM_CODEC (or vice versa). Original: "
                    + payload + ", decoded: " + decoded);
            return;
        }

        helper.succeed();
    }

    /** A non-stackable output holding one item is full. getAdjustedCapacity would give about 1/64. */
    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testComputeDisposalRatioUsesRealCapacityNotAdjustedCapacity(GameTestHelper helper) {
        var outputStack = aztech.modern_industrialization.inventory.ConfigurableItemStack.standardOutputSlot();
        outputStack.setKey(aztech.modern_industrialization.thirdparty.fabrictransfer.api.item.ItemVariant.of(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
        outputStack.setAmount(1);

        if (net.minecraft.world.item.Items.DIAMOND_PICKAXE.getDefaultMaxStackSize() != 1) {
            helper.fail("Test assumption broken: diamond_pickaxe is expected to have max stack size 1.");
            return;
        }

        UnifiedCrafter fakeCrafter = new UnifiedCrafter() {
            @Override
            public boolean hasActiveRecipe() {
                return false;
            }

            @Override
            public @org.jetbrains.annotations.Nullable RecipeHolder<MachineRecipe> getActiveRecipe() {
                return null;
            }

            @Override
            public float getProgress() {
                return 0f;
            }

            @Override
            public List<aztech.modern_industrialization.inventory.ConfigurableItemStack> getItemInputs() {
                return List.of();
            }

            @Override
            public List<aztech.modern_industrialization.inventory.ConfigurableFluidStack> getFluidInputs() {
                return List.of();
            }

            @Override
            public List<aztech.modern_industrialization.inventory.ConfigurableItemStack> getItemOutputs() {
                return List.of(outputStack);
            }

            @Override
            public List<aztech.modern_industrialization.inventory.ConfigurableFluidStack> getFluidOutputs() {
                return List.of();
            }

            @Override
            public @org.jetbrains.annotations.Nullable aztech.modern_industrialization.machines.recipe.MachineRecipeType getRecipeType() {
                return null;
            }

            @Override
            public boolean banRecipe(MachineRecipe recipe) {
                return false;
            }
        };

        double ratio = ServerMonitoringManager.computeDisposalRatio(fakeCrafter);
        if (Math.abs(ratio - 1.0) > 0.001) {
            helper.fail("Expected a full non-stackable output slot (1 held / 1 real capacity) to compute "
                    + "disposalRatio ~1.0, but got: " + ratio + ". A value near 1/64 (~0.0156) means "
                    + "computeDisposalRatio is using getAdjustedCapacity() instead of getCapacity().");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testPacketRateLimiterThrottling(GameTestHelper helper) {
        com.mervyn.miforeman.network.PacketRateLimiter limiter = new com.mervyn.miforeman.network.PacketRateLimiter(10);

        java.util.UUID playerA = java.util.UUID.randomUUID();
        java.util.UUID playerB = java.util.UUID.randomUUID();

        if (!limiter.tryAcquire(playerA, 0L)) {
            helper.fail("Expected first tryAcquire for a fresh player to be allowed.");
            return;
        }
        if (limiter.tryAcquire(playerA, 5L)) {
            helper.fail("Expected tryAcquire within minIntervalTicks (5 < 10) of the last call to be rejected.");
            return;
        }
        if (!limiter.tryAcquire(playerA, 10L)) {
            helper.fail("Expected tryAcquire exactly minIntervalTicks after the last call to be allowed.");
            return;
        }

        if (!limiter.tryAcquire(playerB, 10L)) {
            helper.fail("Expected a different player's first tryAcquire to be allowed independently "
                    + "of another player's throttling state.");
            return;
        }
        if (limiter.tryAcquire(playerB, 15L)) {
            helper.fail("Expected playerB's tryAcquire within its own minIntervalTicks to be rejected.");
            return;
        }

        if (!limiter.tryAcquire(playerA, 20L)) {
            helper.fail("Expected playerA to be allowed again after its own interval elapsed, "
                    + "independent of playerB's state.");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testAmbiguityWrapAroundCycling(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal goal = new ProductionGoal("wraparound_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);

        RecipeGraphTraverser.clearGraphCache();
        var initialGraph = RecipeGraphTraverser.computeRecipeGraph(level, goal);

        var ambiguousNode = initialGraph.nodes().values().stream()
                .filter(node -> node.getType() != com.mervyn.miforeman.goal.NodeType.MACHINE
                        && node.getAmbiguityOptions().size() >= 3)
                .findFirst()
                .orElse(null);

        if (ambiguousNode == null) {
            helper.fail("Expected at least one ambiguous resource node with 3+ recipe options in "
                    + "the quantum_upgrade recipe graph.");
            return;
        }

        List<ResourceLocation> options = ambiguousNode.getAmbiguityOptions();
        ResourceLocation ownerId = ambiguousNode.getAmbiguityOwnerId();

        int initialNodeCount = initialGraph.nodes().size();
        int initialEdgeCount = initialGraph.edges().size();

        for (int i = 1; i <= options.size(); i++) {
            ResourceLocation nextRecipe = options.get(i % options.size());
            ProductionGoal cycledGoal = goal.withRecipeSelections(Map.of(ownerId, nextRecipe));
            var cycledGraph = RecipeGraphTraverser.computeRecipeGraph(level, cycledGoal);

            var cycledNode = cycledGraph.nodes().get(ambiguousNode.getId());
            if (cycledNode == null) {
                helper.fail("Ambiguous resource node " + ambiguousNode.getId()
                        + " disappeared from the graph after selecting option " + nextRecipe);
                return;
            }
            if (!nextRecipe.equals(cycledNode.getSelectedAmbiguity())) {
                helper.fail("Expected selected ambiguity " + nextRecipe + " after cycling, but got: "
                        + cycledNode.getSelectedAmbiguity());
                return;
            }

            if (!cycledGraph.nodes().containsKey(nextRecipe)) {
                helper.fail("After selecting " + nextRecipe + ", recipe node was missing from graph.");
                return;
            }
        }

        ProductionGoal backToStartGoal = goal.withRecipeSelections(Map.of(ownerId, options.get(0)));
        var finalGraph = RecipeGraphTraverser.computeRecipeGraph(level, backToStartGoal);

        if (finalGraph.nodes().size() != initialNodeCount) {
            helper.fail("Expected node count to return to " + initialNodeCount
                    + " after a full wrap-around cycle, but got: " + finalGraph.nodes().size());
            return;
        }
        if (finalGraph.edges().size() != initialEdgeCount) {
            helper.fail("Expected edge count to return to " + initialEdgeCount
                    + " after a full wrap-around cycle, but got: " + finalGraph.edges().size());
            return;
        }

        RecipeGraphTraverser.clearGraphCache();
        helper.succeed();
    }

    /** MI Tweaks sends a login packet the mock connection can't take. Login is the last step of placeNewPlayer, so the player is already listed when it throws. */
    @SuppressWarnings("removal") // Deprecated upstream, but still the only way to get a real ServerPlayer in a gametest.
    private static net.minecraft.server.level.ServerPlayer mockServerPlayer(GameTestHelper helper) {
        try {
            return helper.makeMockServerPlayerInLevel();
        } catch (UnsupportedOperationException e) {
            List<net.minecraft.server.level.ServerPlayer> players = helper.getLevel().getServer().getPlayerList().getPlayers();
            if (e.getMessage() == null || !e.getMessage().contains("mi_tweaks:") || players.isEmpty())
                throw e;
            var player = players.get(players.size() - 1);
            if (!player.getGameProfile().getName().equals("test-mock-player"))
                throw e;
            return player;
        }
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID, timeoutTicks = 200)
    public static void testMachineStatusDynamicTransitions(GameTestHelper helper) {
        var level = helper.getLevel();

        // Needs the electric compressor, since the bronze one has no EnergyComponent to fill.
        var compressorBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("modern_industrialization:electric_compressor"));
        BlockPos relativePos = new BlockPos(1, 2, 1);
        BlockPos absolutePos = helper.absolutePos(relativePos);
        helper.setBlock(relativePos, compressorBlock);

        BlockEntity be = level.getBlockEntity(absolutePos);
        if (!(be instanceof MachineBlockEntity machine)) {
            helper.fail("Placed block is not a MachineBlockEntity!");
            return;
        }

        UnifiedCrafter crafter = ServerMonitoringManager.getCrafter(machine);
        if (crafter == null) {
            helper.fail("Placed Compressor does not have a CrafterComponent!");
            return;
        }

        // Trackers are global static state.
        ServerMonitoringManager.TRACKERS.clear();

        // onServerTick only tracks machines linked from a held clipboard.
        net.minecraft.server.level.ServerPlayer mockPlayer = mockServerPlayer(helper);
        GlobalPos key = ServerMonitoringManager.key(level, absolutePos);

        ProductionGoal goal = new ProductionGoal(
                "dynamic_status_test",
                ProductionGoal.TargetType.ITEM,
                ResourceLocation.parse("modern_industrialization:iron_plate"),
                1.0).withLinkedMachines(List.of(key), com.mervyn.miforeman.goal.MachineLinkHistory.EMPTY);

        ItemStack clipboard = new ItemStack(ModItems.FOREMAN_CLIPBOARD_ITEM.get());
        clipboard.set(ModComponents.PRODUCTION_GOAL.get(), goal);
        mockPlayer.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, clipboard);

        helper.runAfterDelay(3, () -> {
            var tracker = ServerMonitoringManager.TRACKERS.get(key);
            if (tracker == null) {
                helper.fail("Expected onServerTick to create a MachineTracker for the linked machine.");
                return;
            }
            if (tracker.status != com.mervyn.miforeman.goal.MachineStatus.RED) {
                helper.fail("Expected empty-input machine to read RED, but got: " + tracker.status);
                return;
            }

            var inputSlot = crafter.getItemInputs().get(0);
            inputSlot.setKey(aztech.modern_industrialization.thirdparty.fabrictransfer.api.item.ItemVariant
                    .of(net.minecraft.world.item.Items.IRON_INGOT));
            inputSlot.setAmount(4);

            var energyComponent = (aztech.modern_industrialization.machines.components.EnergyComponent) ((aztech.modern_industrialization.api.machine.holder.EnergyComponentHolder) machine)
                    .getEnergyComponent();
            energyComponent.insertEu(Long.MAX_VALUE, aztech.modern_industrialization.util.Simulation.ACT);

            helper.runAfterDelay(5, () -> {
                var trackerAfterFeed = ServerMonitoringManager.TRACKERS.get(key);
                if (trackerAfterFeed == null || trackerAfterFeed.status != com.mervyn.miforeman.goal.MachineStatus.GREEN) {
                    helper.fail("Expected actively-crafting machine to read GREEN, but got: "
                            + (trackerAfterFeed != null ? trackerAfterFeed.status : "null"));
                    return;
                }
                if (!crafter.hasActiveRecipe()) {
                    helper.fail("Tracker read GREEN but crafter.hasActiveRecipe() is false.");
                    return;
                }

                var outputSlot = ((UnifiedCrafter.StandardCrafterAdapter) crafter).getUnderlying().getInventory()
                        .getItemOutputs().get(0);
                outputSlot.setKey(aztech.modern_industrialization.thirdparty.fabrictransfer.api.item.ItemVariant
                        .of(net.minecraft.world.item.Items.GLASS));
                outputSlot.setAmount(64);

                helper.succeedWhen(() -> {
                    var finalTracker = ServerMonitoringManager.TRACKERS.get(key);
                    if (finalTracker == null || finalTracker.status != com.mervyn.miforeman.goal.MachineStatus.ORANGE) {
                        helper.fail("Expected saturated machine to read ORANGE, but got: "
                                + (finalTracker != null ? finalTracker.status : "null"));
                    }
                });
            });
        });
    }

    /** Proxied types change results even with no addons, since MI's furnace type synthesizes a MachineRecipe for every vanilla smelting recipe. So output is not compared before and after. */
    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testProxiedRecipeTypesConfigDefaultsOffAndTogglesCleanly(GameTestHelper helper) {
        if (com.mervyn.miforeman.Config.INCLUDE_PROXIED_RECIPE_TYPES.get()) {
            helper.fail("Expected includeProxiedRecipeTypes to default to false.");
            return;
        }

        var level = helper.getLevel();
        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal goal = new ProductionGoal("proxied_config_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);

        com.mervyn.miforeman.Config.INCLUDE_PROXIED_RECIPE_TYPES.set(true);
        try {
            RecipeGraphTraverser.clearGraphCache();
            var graphAfter = RecipeGraphTraverser.computeRecipeGraph(level, goal);
            ProductionGoal.FactoryPlan planAfter = RecipeGraphTraverser.computePlan(level, goal);

            if (graphAfter.nodes().isEmpty() || graphAfter.edges().isEmpty()) {
                helper.fail("Expected a non-empty graph with includeProxiedRecipeTypes on, but got: "
                        + graphAfter.nodes().size() + " nodes / " + graphAfter.edges().size() + " edges.");
                return;
            }
            if (planAfter.machines().isEmpty()) {
                helper.fail("Expected non-empty machine requirements with includeProxiedRecipeTypes on.");
                return;
            }
        } finally {
            com.mervyn.miforeman.Config.INCLUDE_PROXIED_RECIPE_TYPES.set(false);
            RecipeGraphTraverser.clearGraphCache();
        }

        var graphRestored = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        if (graphRestored.nodes().size() != 536 || graphRestored.edges().size() != 790) {
            helper.fail("Expected graph to return to the pinned 536 nodes/790 edges after disabling "
                    + "includeProxiedRecipeTypes again, but got: " + graphRestored.nodes().size()
                    + " nodes / " + graphRestored.edges().size() + " edges.");
            return;
        }

        helper.succeed();
    }

    public static class MockModularCrafter {
        public boolean hasActive = true;
        public RecipeHolder<MachineRecipe> activeRecipe;
        public float progress = 0.75f;
        public MockInventory inv = new MockInventory();
        public MockBehavior behavior = new MockBehavior();

        public MockModularCrafter(RecipeHolder<MachineRecipe> recipe) {
            this.activeRecipe = recipe;
        }

        public boolean hasActiveRecipe() {
            return hasActive;
        }

        public RecipeHolder<MachineRecipe> getActiveRecipe() {
            return activeRecipe;
        }

        public float getProgress() {
            return progress;
        }

        public MockInventory getInventory() {
            return inv;
        }

        public MockBehavior getBehavior() {
            return behavior;
        }
    }

    public static class MockInventory {
        public List<aztech.modern_industrialization.inventory.ConfigurableItemStack> getItemInputs() {
            return List.of();
        }

        public List<aztech.modern_industrialization.inventory.ConfigurableFluidStack> getFluidInputs() {
            return List.of();
        }
    }

    public static class MockBehavior {
        public aztech.modern_industrialization.machines.recipe.MachineRecipeType recipeType() {
            return aztech.modern_industrialization.machines.init.MIMachineRecipeTypes.COMPRESSOR;
        }

        public boolean banRecipe(MachineRecipe recipe) {
            return false;
        }
    }

    /** Simulates MultipliedCrafterComponent / modular multiblocks where recipeType() is directly on the component. */
    public static class MockDirectModularCrafter {
        public boolean hasActive = true;
        protected RecipeHolder<MachineRecipe> activeRecipe;
        protected float progress = 0.5f;
        protected MockInventory inv = new MockInventory();
        protected Object behavior = new Object(); // no recipeType or banRecipe methods

        public MockDirectModularCrafter(RecipeHolder<MachineRecipe> recipe) {
            this.activeRecipe = recipe;
        }

        public boolean hasActiveRecipe() {
            return hasActive;
        }

        public float getProgress() {
            return progress;
        }

        public MockInventory getInventory() {
            return inv;
        }

        public Object getBehavior() {
            return behavior;
        }

        public aztech.modern_industrialization.machines.recipe.MachineRecipeType getRecipeType() {
            return aztech.modern_industrialization.machines.init.MIMachineRecipeTypes.COMPRESSOR;
        }

        public boolean banRecipe(MachineRecipe recipe) {
            return false;
        }
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testUnifiedCrafterStandardParity(GameTestHelper helper) {
        BlockPos machinePos = new BlockPos(1, 1, 1);
        var compressorBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("modern_industrialization:bronze_compressor"));
        helper.setBlock(machinePos, compressorBlock);

        BlockEntity be = helper.getBlockEntity(machinePos);
        if (!(be instanceof MachineBlockEntity machine)) {
            helper.fail("Placed block is not a MachineBlockEntity!");
            return;
        }

        UnifiedCrafter crafter = ServerMonitoringManager.getCrafter(machine);
        if (crafter == null) {
            helper.fail("ServerMonitoringManager.getCrafter(machine) returned null for bronze compressor!");
            return;
        }

        if (!(crafter instanceof UnifiedCrafter.StandardCrafterAdapter)) {
            helper.fail(
                    "Expected StandardCrafterAdapter for standard MI machine, got: " + crafter.getClass().getName());
            return;
        }

        if (crafter.hasActiveRecipe()) {
            helper.fail("Newly placed compressor should not have active recipe.");
            return;
        }

        if (crafter.getRecipeType() != aztech.modern_industrialization.machines.init.MIMachineRecipeTypes.COMPRESSOR) {
            helper.fail("Expected COMPRESSOR recipe type, got: " + crafter.getRecipeType());
            return;
        }

        if (crafter.getItemInputs().isEmpty()) {
            helper.fail("Expected bronze compressor to have item inputs in its inventory.");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testUnifiedCrafterModularDuckTyping(GameTestHelper helper) {
        var recipeManager = helper.getLevel().getRecipeManager();
        var candidates = RecipeGraphTraverser.groupMachineRecipesByType(recipeManager);
        var compressorRecipes = candidates.get(ResourceLocation.parse("modern_industrialization:compressor"));
        if (compressorRecipes == null || compressorRecipes.isEmpty()) {
            helper.fail("Could not find compressor recipes in RecipeManager.");
            return;
        }

        RecipeHolder<MachineRecipe> testHolder = compressorRecipes.get(0);
        MockModularCrafter mock = new MockModularCrafter(testHolder);

        var accessors = UnifiedCrafter.ModularAccessors.get(MockModularCrafter.class);
        if (accessors == null) {
            helper.fail("ModularAccessors.get(MockModularCrafter.class) returned null!");
            return;
        }

        UnifiedCrafter unified = new UnifiedCrafter.ModularCrafterAdapter(mock, accessors);

        if (!unified.hasActiveRecipe()) {
            helper.fail("Expected hasActiveRecipe() to return true.");
            return;
        }

        RecipeHolder<MachineRecipe> extracted = unified.getActiveRecipe();
        if (extracted == null || !extracted.id().equals(testHolder.id())) {
            helper.fail("Expected active recipe ID " + testHolder.id() + ", got: "
                    + (extracted != null ? extracted.id() : "null"));
            return;
        }

        if (Math.abs(unified.getProgress() - 0.75f) > 0.001f) {
            helper.fail("Expected progress 0.75, got: " + unified.getProgress());
            return;
        }

        if (unified.getRecipeType() != aztech.modern_industrialization.machines.init.MIMachineRecipeTypes.COMPRESSOR) {
            helper.fail("Expected COMPRESSOR recipe type, got: " + unified.getRecipeType());
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testUnifiedCrafterDirectModularDuckTyping(GameTestHelper helper) {
        var recipeManager = helper.getLevel().getRecipeManager();
        var candidates = RecipeGraphTraverser.groupMachineRecipesByType(recipeManager);
        var compressorRecipes = candidates.get(ResourceLocation.parse("modern_industrialization:compressor"));
        if (compressorRecipes == null || compressorRecipes.isEmpty()) {
            helper.fail("Could not find compressor recipes in RecipeManager.");
            return;
        }

        RecipeHolder<MachineRecipe> testHolder = compressorRecipes.get(0);
        MockDirectModularCrafter mock = new MockDirectModularCrafter(testHolder);

        var accessors = UnifiedCrafter.ModularAccessors.get(MockDirectModularCrafter.class);
        if (accessors == null) {
            helper.fail("ModularAccessors.get(MockDirectModularCrafter.class) returned null!");
            return;
        }

        UnifiedCrafter unified = new UnifiedCrafter.ModularCrafterAdapter(mock, accessors);

        if (!unified.hasActiveRecipe()) {
            helper.fail("Expected hasActiveRecipe() to return true.");
            return;
        }

        RecipeHolder<MachineRecipe> extracted = unified.getActiveRecipe();
        if (extracted == null || !extracted.id().equals(testHolder.id())) {
            helper.fail("Expected active recipe ID " + testHolder.id() + ", got: "
                    + (extracted != null ? extracted.id() : "null"));
            return;
        }

        if (Math.abs(unified.getProgress() - 0.5f) > 0.001f) {
            helper.fail("Expected progress 0.5, got: " + unified.getProgress());
            return;
        }

        if (unified.getRecipeType() != aztech.modern_industrialization.machines.init.MIMachineRecipeTypes.COMPRESSOR) {
            helper.fail("Expected COMPRESSOR recipe type from direct component method, got: " + unified.getRecipeType());
            return;
        }

        helper.succeed();
    }

    private static Map<ResourceLocation, List<String>> searchableNodeTexts(
            java.util.Collection<com.mervyn.miforeman.goal.RecipeGraphNode> nodes) {
        Map<ResourceLocation, List<String>> texts = new java.util.HashMap<>();
        for (var node : nodes) {
            ResourceLocation id = node.getId();
            texts.put(id, List.of(id.toString(), id.getPath(), com.mervyn.miforeman.client.DisplayFormat.formatId(id)));
        }
        return texts;
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGraphSearchMatchingLogic(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ProductionGoal goal = new ProductionGoal("search_test", ProductionGoal.TargetType.ITEM,
                ResourceLocation.parse("modern_industrialization:quantum_upgrade"), 1.0);
        com.mervyn.miforeman.goal.RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        if (graph.nodes().isEmpty()) {
            helper.fail("Expected quantum_upgrade graph to contain nodes.");
            return;
        }

        com.mervyn.miforeman.client.gui.widget.SearchState<ResourceLocation> state =
                new com.mervyn.miforeman.client.gui.widget.SearchState<>();
        Map<ResourceLocation, List<String>> texts = searchableNodeTexts(graph.nodes().values());

        state.setQuery("", texts);
        if (state.isSearching() || state.getMatchCount() != 0 || state.getCurrentIndex() != -1) {
            helper.fail("Empty query should have 0 matches and isSearching == false");
            return;
        }

        state.setQuery("assembler", texts);
        if (!state.isSearching() || state.getMatchCount() == 0) {
            helper.fail("Expected matches for query 'assembler' in quantum_upgrade graph");
            return;
        }
        for (ResourceLocation matchId : state.getMatches()) {
            String formatted = com.mervyn.miforeman.client.DisplayFormat.formatId(matchId)
                    .toLowerCase(java.util.Locale.ROOT);
            String raw = matchId.toString().toLowerCase(java.util.Locale.ROOT);
            if (!formatted.contains("assembler") && !raw.contains("assembler")) {
                helper.fail("Match " + matchId + " does not contain query 'assembler'");
                return;
            }
        }

        state.setQuery("modern_industrialization:quantum_upgrade", texts);
        if (state.getMatchCount() != 1) {
            helper.fail("Expected exactly 1 match for full quantum_upgrade ID, got: " + state.getMatchCount());
            return;
        }
        if (!state.getMatches().get(0).equals(ResourceLocation.parse("modern_industrialization:quantum_upgrade"))) {
            helper.fail(
                    "Expected match to be modern_industrialization:quantum_upgrade, got: " + state.getMatches().get(0));
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGraphSearchMatchCycling(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ProductionGoal goal = new ProductionGoal("cycle_test", ProductionGoal.TargetType.ITEM,
                ResourceLocation.parse("modern_industrialization:quantum_upgrade"), 1.0);
        com.mervyn.miforeman.goal.RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);

        com.mervyn.miforeman.client.gui.widget.SearchState<ResourceLocation> state =
                new com.mervyn.miforeman.client.gui.widget.SearchState<>();
        state.setQuery("assembler", searchableNodeTexts(graph.nodes().values()));

        int count = state.getMatchCount();
        if (count < 2) {
            helper.fail("Expected at least 2 assembler matches for cycling test, got: " + count);
            return;
        }

        if (state.getCurrentIndex() != 0) {
            helper.fail("Initial index expected 0, got: " + state.getCurrentIndex());
            return;
        }

        ResourceLocation secondMatch = state.nextMatch();
        if (state.getCurrentIndex() != 1 || secondMatch == null || !secondMatch.equals(state.getMatches().get(1))) {
            helper.fail("Expected nextMatch() to advance to index 1");
            return;
        }

        ResourceLocation firstMatch = state.prevMatch();
        if (state.getCurrentIndex() != 0 || firstMatch == null || !firstMatch.equals(state.getMatches().get(0))) {
            helper.fail("Expected prevMatch() to return to index 0");
            return;
        }

        ResourceLocation lastMatch = state.prevMatch();
        if (state.getCurrentIndex() != count - 1 || lastMatch == null
                || !lastMatch.equals(state.getMatches().get(count - 1))) {
            helper.fail("Expected wrap-around backwards to index " + (count - 1) + ", got: " + state.getCurrentIndex());
            return;
        }

        ResourceLocation wrappedFirst = state.nextMatch();
        if (state.getCurrentIndex() != 0 || wrappedFirst == null || !wrappedFirst.equals(state.getMatches().get(0))) {
            helper.fail("Expected wrap-around forward to index 0, got: " + state.getCurrentIndex());
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testSearchStateGenericOverArbitraryId(GameTestHelper helper) {
        Map<String, List<String>> texts = Map.of(
                "iron_press", List.of("Iron Press", "modid:iron_press"),
                "gold_press", List.of("Gold Press", "modid:gold_press"),
                "furnace", List.of("Furnace", "modid:furnace"));

        var state = new com.mervyn.miforeman.client.gui.widget.SearchState<String>();

        state.setQuery("PRESS", texts);
        if (state.getMatchCount() != 2 || !state.isMatch("iron_press") || !state.isMatch("gold_press")
                || state.isMatch("furnace")) {
            helper.fail("Expected 'PRESS' (case-insensitive) to match iron_press/gold_press only, got matches: "
                    + state.getMatches());
            return;
        }

        int count = state.getMatchCount();
        if (state.getCurrentIndex() != 0) {
            helper.fail("Initial index expected 0, got: " + state.getCurrentIndex());
            return;
        }
        String second = state.nextMatch();
        if (state.getCurrentIndex() != 1 || !java.util.Objects.equals(second, state.getMatches().get(1))) {
            helper.fail("Expected nextMatch() to advance to index 1");
            return;
        }
        state.prevMatch();
        String wrappedFurtherBack = state.prevMatch();
        if (state.getCurrentIndex() != count - 1
                || !java.util.Objects.equals(wrappedFurtherBack, state.getMatches().get(count - 1))) {
            helper.fail("Expected prevMatch() twice from index 1 to wrap to index " + (count - 1) + ", got: "
                    + state.getCurrentIndex());
            return;
        }

        state.setQuery("", texts);
        if (state.isSearching() || state.getMatchCount() != 0 || state.getCurrentIndex() != -1) {
            helper.fail("Empty query should reset to 0 matches and isSearching == false");
            return;
        }
        state.setQuery("nonexistent", texts);
        if (state.getMatchCount() != 0 || state.currentMatchId() != null) {
            helper.fail("Query with no matches should have 0 matches and a null current match");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGraphCameraCenteringMath(GameTestHelper helper) {
        // Node at (200, 100) with size 96x26 -> center is (248, 113)
        // Canvas size 400x300 -> center is (200, 150)
        // At zoom 1.0: panX = 200 - 248*1 = -48, panY = 150 - 113*1 = 37
        com.mervyn.miforeman.client.gui.widget.GraphCamera camera1 = new com.mervyn.miforeman.client.gui.widget.GraphCamera(
                0, 0, 1.0f);
        camera1.centerOn(400, 300, 200, 100, 96, 26);

        if (Math.abs(camera1.panX() - (-48.0)) > 0.001 || Math.abs(camera1.panY() - 37.0) > 0.001) {
            helper.fail("Expected pan (-48, 37) at zoom 1.0, got: (" + camera1.panX() + ", " + camera1.panY() + ")");
            return;
        }

        // At zoom 2.0: panX = 200 - 248*2 = -296, panY = 150 - 113*2 = -76
        com.mervyn.miforeman.client.gui.widget.GraphCamera camera2 = new com.mervyn.miforeman.client.gui.widget.GraphCamera(
                0, 0, 2.0f);
        camera2.centerOn(400, 300, 200, 100, 96, 26);

        if (Math.abs(camera2.panX() - (-296.0)) > 0.001 || Math.abs(camera2.panY() - (-76.0)) > 0.001) {
            helper.fail("Expected pan (-296, -76) at zoom 2.0, got: (" + camera2.panX() + ", " + camera2.panY() + ")");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID, timeoutTicks = 400)
    public static void testServerGoalSaveComputesPlanOffThread(GameTestHelper helper) {
        var player = mockServerPlayer(helper);
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(ModItems.FOREMAN_CLIPBOARD_ITEM.get()));
        List<Runnable> background = new java.util.ArrayList<>();
        List<Runnable> main = new java.util.ArrayList<>();
        Runnable runBackgroundNewestFirst = () -> {
            while (!background.isEmpty())
                background.remove(background.size() - 1).run();
        };
        Runnable runMain = () -> {
            while (!main.isEmpty())
                main.remove(0).run();
        };
        ResourceLocation plate = ResourceLocation.parse("modern_industrialization:steel_plate");
        // Odd rates so none of these plans is cached already.
        java.util.function.DoubleFunction<com.mervyn.miforeman.network.GoalUpdatePayload> save = rate ->
                new com.mervyn.miforeman.network.GoalUpdatePayload(
                        new ProductionGoal("async_" + rate, ProductionGoal.TargetType.ITEM, plate, rate),
                        net.minecraft.world.InteractionHand.MAIN_HAND);
        java.util.function.Supplier<ProductionGoal> held = () -> player.getMainHandItem().get(ModComponents.PRODUCTION_GOAL.get());

        com.mervyn.miforeman.network.GoalUpdateHandler.update(player, save.apply(7.31), background::add, main::add);
        if (held.get() == null || held.get().rate() != 7.31 || held.get().plan().isPresent() || background.isEmpty()) {
            helper.fail("An uncached save must land at once and leave its plan to the background, got " + held.get());
            return;
        }
        GlobalPos linkedMeanwhile = GlobalPos.of(helper.getLevel().dimension(), new BlockPos(1, 2, 3));
        player.getMainHandItem().set(ModComponents.PRODUCTION_GOAL.get(),
                held.get().withLinkedMachines(List.of(linkedMeanwhile), held.get().machineLinkHistory()));
        runBackgroundNewestFirst.run();
        runMain.run();
        if (held.get().plan().isEmpty() || !held.get().linkedMachines().contains(linkedMeanwhile)) {
            helper.fail("The plan must be patched in without undoing what changed meanwhile, got " + held.get());
            return;
        }

        com.mervyn.miforeman.network.GoalUpdateHandler.update(player, save.apply(7.32), background::add, main::add);
        com.mervyn.miforeman.network.GoalUpdateHandler.update(player, save.apply(7.33), background::add, main::add);
        runBackgroundNewestFirst.run();
        runMain.run();
        if (held.get().rate() != 7.33) {
            helper.fail("The newest save must win even when the older one finishes last, got rate " + held.get().rate());
            return;
        }

        com.mervyn.miforeman.network.GoalUpdateHandler.update(player, save.apply(7.34), background::add, main::add);
        RecipeGraphTraverser.computeRecipeGraph(helper.getLevel(), save.apply(7.35).goal());
        com.mervyn.miforeman.network.GoalUpdateHandler.update(player, save.apply(7.35), background::add, main::add);
        if (held.get().rate() != 7.35) {
            helper.fail("A cached plan must apply straight away, got rate " + held.get().rate());
            return;
        }
        runBackgroundNewestFirst.run();
        runMain.run();
        if (held.get().rate() != 7.35) {
            helper.fail("An older save still computing must not overwrite a newer cached one, got rate " + held.get().rate());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testPlanWorkerAppliesOnlyTheNewestPlan(GameTestHelper helper) {
        List<Runnable> background = new java.util.ArrayList<>();
        List<Runnable> main = new java.util.ArrayList<>();
        var worker = new com.mervyn.miforeman.goal.PlanWorker(background::add, main::add);
        List<String> delivered = new java.util.ArrayList<>();
        Runnable runAll = () -> {
            while (!background.isEmpty())
                background.remove(background.size() - 1).run();
            while (!main.isEmpty())
                main.remove(0).run();
        };

        java.util.concurrent.atomic.AtomicInteger computed = new java.util.concurrent.atomic.AtomicInteger();
        worker.submit(() -> { computed.incrementAndGet(); return "old"; }, (plan, error) -> delivered.add(plan));
        worker.submit(() -> { computed.incrementAndGet(); return "new"; }, (plan, error) -> delivered.add(plan));
        if (!worker.pending()) {
            helper.fail("A submitted plan must count as pending until it's delivered");
            return;
        }
        runAll.run();
        if (computed.get() != 1) {
            helper.fail("A request already replaced must not be computed at all; computed " + computed.get());
            return;
        }
        if (!delivered.equals(List.of("new")) || worker.pending()) {
            helper.fail("Only the newest plan may be applied, even when the older one finishes last; got "
                    + delivered + ", pending " + worker.pending());
            return;
        }

        worker.submit(() -> "closed", (plan, error) -> delivered.add(plan));
        worker.cancel();
        runAll.run();
        if (delivered.size() != 1 || worker.pending()) {
            helper.fail("A cancelled plan must never be applied; got " + delivered);
            return;
        }

        List<Exception> errors = new java.util.ArrayList<>();
        worker.submit(() -> { throw new IllegalStateException("boom"); }, (plan, error) -> errors.add(error));
        runAll.run();
        if (errors.size() != 1 || !(errors.get(0) instanceof IllegalStateException)) {
            helper.fail("A failed plan must reach the callback as its error; got " + errors);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testEdgeEmphasisKeepsWireColour(GameTestHelper helper) {
        var normal = com.mervyn.miforeman.client.gui.widget.EdgeEmphasis.NORMAL;
        var highlighted = com.mervyn.miforeman.client.gui.widget.EdgeEmphasis.HIGHLIGHTED;
        var dimmed = com.mervyn.miforeman.client.gui.widget.EdgeEmphasis.DIMMED;
        int plain = 0xFF8A7A68;
        int stalled = 0x55CC2222;
        if (normal.apply(plain) != plain || normal.apply(stalled) != stalled) {
            helper.fail("NORMAL must leave a wire's colour alone");
            return;
        }
        if (highlighted.apply(stalled) != 0xFFCC2222 || highlighted.apply(plain) != plain) {
            helper.fail("HIGHLIGHTED must keep the colour at full opacity, got "
                    + Integer.toHexString(highlighted.apply(stalled)));
            return;
        }
        if (dimmed.apply(plain) != 0x558A7A68 || dimmed.apply(stalled) != stalled
                || dimmed.apply(0x22CC2222) != 0x22CC2222) {
            helper.fail("DIMMED must keep the colour and cap its opacity, got "
                    + Integer.toHexString(dimmed.apply(plain)));
            return;
        }
        if (highlighted.apply(stalled, 0xFF3366FF) != 0xFF3366FF || dimmed.apply(plain, 0xFF3366FF) != 0x558A7A68
                || normal.apply(stalled, 0xFF3366FF) != stalled) {
            helper.fail("A picked highlight colour must replace only highlighted wires' colour");
            return;
        }
        if (com.mervyn.miforeman.client.gui.widget.EdgeEmphasis.glow(0xFFCC2222, null) != 0x40CC2222
                || com.mervyn.miforeman.client.gui.widget.EdgeEmphasis.glow(0xFFCC2222, 0x00000000) != 0) {
            helper.fail("The glow must default to a faint band of the wire's colour and follow a picked one");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testLabelDetailCutoff(GameTestHelper helper) {
        var bars = com.mervyn.miforeman.client.gui.widget.LabelDetail.BARS;
        var text = com.mervyn.miforeman.client.gui.widget.LabelDetail.TEXT;
        // The vanilla font's line is 9 units, so text needs 6 / 9 = 0.67 of a pixel per unit.
        Object[][] cases = {
                {0.25f, 2.0, bars}, {0.33f, 2.0, bars}, {0.34f, 2.0, text}, {1.0f, 2.0, text},
                {0.5f, 1.0, bars}, {0.67f, 1.0, text}, {0.25f, 3.0, text}, {0.16f, 4.0, bars},
        };
        for (Object[] c : cases) {
            var got = com.mervyn.miforeman.client.gui.widget.LabelDetail.of((float) c[0], (double) c[1], 9);
            if (got != c[2]) {
                helper.fail("zoom " + c[0] + " at GUI scale " + c[1] + " should draw " + c[2] + ", got " + got);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testOverlappingCards(GameTestHelper helper) {
        List<com.mervyn.miforeman.goal.NodePosition> positions = List.of(
                new com.mervyn.miforeman.goal.NodePosition(0, 0), new com.mervyn.miforeman.goal.NodePosition(140, 0),       // apart
                new com.mervyn.miforeman.goal.NodePosition(0, 40), new com.mervyn.miforeman.goal.NodePosition(96, 40),      // touching edges only
                new com.mervyn.miforeman.goal.NodePosition(-150, -100), new com.mervyn.miforeman.goal.NodePosition(-60, -80), // overlapping across a cell line
                new com.mervyn.miforeman.goal.NodePosition(300, 300), new com.mervyn.miforeman.goal.NodePosition(300, 300)); // stacked exactly
        boolean[] got = com.mervyn.miforeman.goal.GraphLayoutEngine.overlapping(positions, 96, 26);
        boolean[] want = {false, false, false, false, true, true, true, true};
        if (!java.util.Arrays.equals(got, want)) {
            helper.fail("Expected overlaps " + java.util.Arrays.toString(want) + ", got "
                    + java.util.Arrays.toString(got));
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGraphCameraViewportCulling(GameTestHelper helper) {
        // Pan (-100, 50) at zoom 2 on a 400x300 widget shows canvas x 50..250, y -25..125.
        var camera = new com.mervyn.miforeman.client.gui.widget.GraphCamera(-100, 50, 2.0f);
        var view = camera.viewport(400, 300, 0);
        if (Math.abs(view.minX() - 50) > 1e-9 || Math.abs(view.minY() - (-25)) > 1e-9
                || Math.abs(view.maxX() - 250) > 1e-9 || Math.abs(view.maxY() - 125) > 1e-9) {
            helper.fail("Expected viewport (50, -25)-(250, 125), got " + view);
            return;
        }
        if (!view.intersects(100, 0, 196, 26)) {
            helper.fail("A card fully inside the view must be drawn");
            return;
        }
        if (!view.intersects(240, 120, 336, 146)) {
            helper.fail("A card hanging over the bottom-right corner must be drawn");
            return;
        }
        if (view.intersects(250, 0, 346, 26) || view.intersects(-46, 0, 50, 26)) {
            helper.fail("A card touching the left or right edge from outside must be skipped");
            return;
        }
        if (view.intersects(100, 125, 196, 151) || view.intersects(100, -51, 196, -25)) {
            helper.fail("A card touching the top or bottom edge from outside must be skipped");
            return;
        }
        var padded = camera.viewport(400, 300, 8);
        if (!padded.intersects(255, 0, 351, 26)) {
            helper.fail("The margin must keep something just past the edge, like a wire's casing");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGraphLayoutStateHiddenNodes(GameTestHelper helper) {
        ResourceLocation nodeA = ResourceLocation.parse("minecraft:iron_ingot");
        ResourceLocation nodeB = ResourceLocation.parse("minecraft:iron_ore");
        com.mervyn.miforeman.goal.GraphLayoutState state = com.mervyn.miforeman.goal.GraphLayoutState.EMPTY;

        if (state.isHidden(nodeA)) {
            helper.fail("Initial state should not have nodeA hidden");
            return;
        }

        state = state.withToggledNodeVisibility(nodeA);
        if (!state.isHidden(nodeA) || state.isHidden(nodeB)) {
            helper.fail("Expected nodeA to be hidden and nodeB to be visible");
            return;
        }

        state = state.withToggledNodeVisibility(nodeB);
        if (!state.isHidden(nodeA) || !state.isHidden(nodeB)) {
            helper.fail("Expected both nodeA and nodeB to be hidden");
            return;
        }

        state = state.withToggledNodeVisibility(nodeA);
        if (state.isHidden(nodeA) || !state.isHidden(nodeB)) {
            helper.fail("Expected nodeA visible and nodeB hidden");
            return;
        }

        state = state.withUnhideAll();
        if (state.isHidden(nodeA) || state.isHidden(nodeB) || !state.hiddenNodes().isEmpty()) {
            helper.fail("Expected hiddenNodes to be empty after withUnhideAll");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testMaterialCandidateRecipesAndExpansion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceLocation ironPlate = ResourceLocation.parse("modern_industrialization:iron_plate");
        var candidates = RecipeGraphTraverser.getCandidateRecipes(level, ironPlate);
        if (candidates.isEmpty()) {
            helper.fail("Expected at least 1 candidate recipe for iron_plate");
            return;
        }

        ProductionGoal goal = new ProductionGoal("iron_plate_test", ProductionGoal.TargetType.ITEM, ironPlate, 1.0);
        var plan = RecipeGraphTraverser.computePlan(level, goal);
        if (plan.graph() == null || plan.graph().nodes().isEmpty()) {
            helper.fail("Expected computed plan to have a non-empty recipe graph");
            return;
        }

        com.mervyn.miforeman.goal.RecipeGraphNode plateNode = plan.graph().node(ironPlate);
        if (plateNode == null) {
            helper.fail("Expected graph to contain iron_plate target node");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testStyreneButadieneRubberGraphTraversal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceLocation sbrId = ResourceLocation.parse("modern_industrialization:styrene_butadiene_rubber");
        
        var candidates = RecipeGraphTraverser.getCandidateRecipes(level, sbrId);
        if (candidates.isEmpty()) {
            helper.fail("Expected candidate recipes for styrene_butadiene_rubber in RecipeManager");
            return;
        }

        ProductionGoal goal = new ProductionGoal("sbr_production", ProductionGoal.TargetType.FLUID, sbrId, 1000.0);
        var plan = RecipeGraphTraverser.computePlan(level, goal);
        if (plan.graph() == null || plan.graph().nodes().isEmpty()) {
            helper.fail("Expected styrene_butadiene_rubber plan to have a non-empty recipe graph");
            return;
        }

        var graph = plan.graph();
        com.mervyn.miforeman.goal.RecipeGraphNode rootNode = graph.node(sbrId);
        if (rootNode == null) {
            helper.fail("Expected graph to contain target node for styrene_butadiene_rubber");
            return;
        }

        if (rootNode.getType() != com.mervyn.miforeman.goal.NodeType.TARGET) {
            helper.fail("Expected root node to have NodeType.TARGET, got: " + rootNode.getType());
            return;
        }

        ResourceLocation intermediateFluid = ResourceLocation.parse("modern_industrialization:styrene_butadiene");
        com.mervyn.miforeman.goal.RecipeGraphNode intermediateNode = graph.node(intermediateFluid);
        if (intermediateNode == null) {
            helper.fail("Expected graph to traverse and include intermediate fluid styrene_butadiene");
            return;
        }

        if (intermediateNode.getRequiredRate() <= 0.0) {
            helper.fail("Expected intermediate fluid rate to be positive, got: " + intermediateNode.getRequiredRate());
            return;
        }

        var intermediateCandidates = RecipeGraphTraverser.getCandidateRecipes(level, intermediateFluid);
        if (intermediateCandidates.isEmpty()) {
            helper.fail("Expected candidate recipes for intermediate fluid styrene_butadiene");
            return;
        }

        java.util.Map<ResourceLocation, ResourceLocation> selections = java.util.Map.of(intermediateFluid, intermediateCandidates.get(0).id());
        ProductionGoal expandedGoal = goal.withRecipeSelections(selections);
        var expandedPlan = RecipeGraphTraverser.computePlan(level, expandedGoal);
        var expandedGraph = expandedPlan.graph();
        if (expandedGraph == null) {
            helper.fail("Expected expanded graph to not be null");
            return;
        }

        var expandedIntermediateNode = expandedGraph.node(intermediateFluid);
        if (expandedIntermediateNode == null || expandedIntermediateNode.getType() != com.mervyn.miforeman.goal.NodeType.INTERMEDIATE) {
            helper.fail("Expected expanded intermediate node to have NodeType.INTERMEDIATE");
            return;
        }

        if (expandedPlan.machines().isEmpty()) {
            helper.fail("Expected plan to contain required machines for styrene_butadiene_rubber synthesis");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGraphLayoutStateHistoryMigration(GameTestHelper helper) {
        var nodeA = ResourceLocation.parse("minecraft:iron_ingot");
        var nodeB = ResourceLocation.parse("minecraft:copper_ingot");

        com.google.gson.JsonObject fromA = new com.google.gson.JsonObject();
        fromA.addProperty("x", 0);
        fromA.addProperty("y", 0);
        com.google.gson.JsonObject toA = new com.google.gson.JsonObject();
        toA.addProperty("x", 10);
        toA.addProperty("y", 20);
        com.google.gson.JsonObject moveA = new com.google.gson.JsonObject();
        moveA.addProperty("node", nodeA.toString());
        moveA.add("from", fromA);
        moveA.add("to", toA);

        com.google.gson.JsonObject fromB = new com.google.gson.JsonObject();
        fromB.addProperty("x", 5);
        fromB.addProperty("y", 5);
        com.google.gson.JsonObject toB = new com.google.gson.JsonObject();
        toB.addProperty("x", 15);
        toB.addProperty("y", 25);
        com.google.gson.JsonObject moveB = new com.google.gson.JsonObject();
        moveB.addProperty("node", nodeB.toString());
        moveB.add("from", fromB);
        moveB.add("to", toB);

        com.google.gson.JsonArray oldUndoStack = new com.google.gson.JsonArray();
        oldUndoStack.add(moveA);
        oldUndoStack.add(moveB);

        com.google.gson.JsonObject nodePositions = new com.google.gson.JsonObject();
        nodePositions.add(nodeA.toString(), toA);
        nodePositions.add(nodeB.toString(), toB);

        com.google.gson.JsonObject legacyState = new com.google.gson.JsonObject();
        legacyState.add("node_positions", nodePositions);
        legacyState.add("undo_stack", oldUndoStack);
        legacyState.add("redo_stack", new com.google.gson.JsonArray());

        var result = com.mervyn.miforeman.goal.GraphLayoutState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, legacyState);
        if (result.isError()) {
            helper.fail("GraphLayoutState.CODEC failed to parse a pre-batch-undo save: " + result.error().get().message());
            return;
        }
        var decoded = result.result().get();

        if (decoded.undoStack().size() != 2) {
            helper.fail("Expected 2 undo history entries (one per legacy move), got: " + decoded.undoStack().size());
            return;
        }
        if (decoded.undoStack().get(0).moves().size() != 1 || decoded.undoStack().get(1).moves().size() != 1) {
            helper.fail("Expected each migrated legacy move to become its own singleton batch, got: " + decoded.undoStack());
            return;
        }
        if (!decoded.undoStack().get(0).moves().get(0).nodeId().equals(nodeA)
                || !decoded.undoStack().get(1).moves().get(0).nodeId().equals(nodeB)) {
            helper.fail("Migrated undo history lost node identity or ordering: " + decoded.undoStack());
            return;
        }

        var reencoded = com.mervyn.miforeman.goal.GraphLayoutState.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, decoded);
        if (reencoded.isError()) {
            helper.fail("GraphLayoutState.CODEC failed to re-encode the migrated state: " + reencoded.error().get().message());
            return;
        }
        var roundTrip = com.mervyn.miforeman.goal.GraphLayoutState.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, reencoded.result().get());
        if (roundTrip.isError() || !roundTrip.result().get().equals(decoded)) {
            helper.fail("GraphLayoutState did not round-trip after migration re-encode.");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGraphLayoutStateStreamCodecParity(GameTestHelper helper) {
        var level = helper.getLevel();
        var nodeA = ResourceLocation.parse("minecraft:iron_ingot");
        var nodeB = ResourceLocation.parse("minecraft:copper_ingot");
        var nodeC = ResourceLocation.parse("minecraft:gold_ingot");

        var group = new com.mervyn.miforeman.goal.NodeGroup(java.util.UUID.randomUUID(), Set.of(nodeA, nodeB));
        var batch = List.of(
                new com.mervyn.miforeman.goal.NodeMoveAction(nodeA,
                        new com.mervyn.miforeman.goal.NodePosition(0, 0), new com.mervyn.miforeman.goal.NodePosition(10, 10)),
                new com.mervyn.miforeman.goal.NodeMoveAction(nodeB,
                        new com.mervyn.miforeman.goal.NodePosition(5, 5), new com.mervyn.miforeman.goal.NodePosition(15, 15)));

        var state = new com.mervyn.miforeman.goal.GraphLayoutState(
                Map.of(nodeA, new com.mervyn.miforeman.goal.NodePosition(10, 10),
                        nodeB, new com.mervyn.miforeman.goal.NodePosition(15, 15)),
                List.of(new com.mervyn.miforeman.goal.HistoryEntry(batch)),
                List.of(),
                Set.of(nodeC),
                List.of(group));

        @SuppressWarnings("deprecation")
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                level.registryAccess());
        com.mervyn.miforeman.goal.GraphLayoutState.STREAM_CODEC.encode(buf, state);
        var decoded = com.mervyn.miforeman.goal.GraphLayoutState.STREAM_CODEC.decode(buf);

        if (!decoded.equals(state)) {
            helper.fail("STREAM_CODEC round-trip does not match original GraphLayoutState. Original: "
                    + state + ", decoded: " + decoded);
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGraphLayoutEngineDeterministicAndRigidGroupTranslation(GameTestHelper helper) {
        ResourceLocation targetId = ResourceLocation.parse("miforeman_test:target");
        ResourceLocation machineId = ResourceLocation.parse("miforeman_test:machine");
        ResourceLocation rawAId = ResourceLocation.parse("miforeman_test:raw_a");
        ResourceLocation rawBId = ResourceLocation.parse("miforeman_test:raw_b");

        var target = new com.mervyn.miforeman.goal.RecipeGraphNode(targetId, com.mervyn.miforeman.goal.NodeType.TARGET,
                null, null, 1.0, 0.0, List.of(), null, null, 0);
        var machine = new com.mervyn.miforeman.goal.RecipeGraphNode(machineId, com.mervyn.miforeman.goal.NodeType.MACHINE,
                null, null, 1.0, 1.0, List.of(), null, null, 1);
        var rawA = new com.mervyn.miforeman.goal.RecipeGraphNode(rawAId, com.mervyn.miforeman.goal.NodeType.RAW,
                null, null, 1.0, 0.0, List.of(), null, null, 2);
        var rawB = new com.mervyn.miforeman.goal.RecipeGraphNode(rawBId, com.mervyn.miforeman.goal.NodeType.RAW,
                null, null, 1.0, 0.0, List.of(), null, null, 2);

        var edgeMachineToTarget = new com.mervyn.miforeman.goal.GraphEdge(machineId, targetId, 1.0);
        var edgeRawAToMachine = new com.mervyn.miforeman.goal.GraphEdge(rawAId, machineId, 1.0);
        var edgeRawBToMachine = new com.mervyn.miforeman.goal.GraphEdge(rawBId, machineId, 1.0);
        target.putInput(edgeMachineToTarget);
        machine.putOutput(edgeMachineToTarget);
        machine.putInput(edgeRawAToMachine);
        machine.putInput(edgeRawBToMachine);
        rawA.putOutput(edgeRawAToMachine);
        rawB.putOutput(edgeRawBToMachine);

        Map<ResourceLocation, com.mervyn.miforeman.goal.RecipeGraphNode> nodes = Map.of(
                targetId, target, machineId, machine, rawAId, rawA, rawBId, rawB);
        var graph = new com.mervyn.miforeman.goal.RecipeGraph(targetId, 1.0, nodes,
                List.of(edgeMachineToTarget, edgeRawAToMachine, edgeRawBToMachine), Set.of());

        var group = new com.mervyn.miforeman.goal.NodeGroup(java.util.UUID.randomUUID(), Set.of(rawAId, rawBId));
        Map<ResourceLocation, com.mervyn.miforeman.goal.NodePosition> currentPositions = Map.of(
                targetId, new com.mervyn.miforeman.goal.NodePosition(0, 0),
                machineId, new com.mervyn.miforeman.goal.NodePosition(140, 0),
                rawAId, new com.mervyn.miforeman.goal.NodePosition(280, 0),
                rawBId, new com.mervyn.miforeman.goal.NodePosition(280, 60));

        var result1 = com.mervyn.miforeman.goal.GraphLayoutEngine.arrange(graph, List.of(group), currentPositions, false, 96, 26, 44);
        var result2 = com.mervyn.miforeman.goal.GraphLayoutEngine.arrange(graph, List.of(group), currentPositions, false, 96, 26, 44);

        if (!result1.equals(result2)) {
            helper.fail("GraphLayoutEngine.arrange is not deterministic across identical calls. First: "
                    + result1 + ", second: " + result2);
            return;
        }

        var rawAAfter = result1.get(rawAId);
        var rawBAfter = result1.get(rawBId);
        if (rawAAfter == null || rawBAfter == null) {
            helper.fail("Locked group members are missing from the arrange result: " + result1);
            return;
        }
        int originalDx = currentPositions.get(rawBId).x() - currentPositions.get(rawAId).x();
        int originalDy = currentPositions.get(rawBId).y() - currentPositions.get(rawAId).y();
        int newDx = rawBAfter.x() - rawAAfter.x();
        int newDy = rawBAfter.y() - rawAAfter.y();
        if (originalDx != newDx || originalDy != newDy) {
            helper.fail("A frozen locked group's members should keep their relative offset after auto-arrange. "
                    + "Original offset: (" + originalDx + "," + originalDy + "), after: (" + newDx + "," + newDy + ")");
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGraphLayoutEngineRearrangeInsideGroups(GameTestHelper helper) {
        ResourceLocation targetId = ResourceLocation.parse("miforeman_test:target");
        ResourceLocation machineAId = ResourceLocation.parse("miforeman_test:machine_a");
        ResourceLocation machineBId = ResourceLocation.parse("miforeman_test:machine_b");
        ResourceLocation rawCId = ResourceLocation.parse("miforeman_test:raw_c");

        var target = new com.mervyn.miforeman.goal.RecipeGraphNode(targetId, com.mervyn.miforeman.goal.NodeType.TARGET,
                null, null, 1.0, 0.0, List.of(), null, null, 0);
        var machineA = new com.mervyn.miforeman.goal.RecipeGraphNode(machineAId, com.mervyn.miforeman.goal.NodeType.MACHINE,
                null, null, 1.0, 1.0, List.of(), null, null, 1);
        var machineB = new com.mervyn.miforeman.goal.RecipeGraphNode(machineBId, com.mervyn.miforeman.goal.NodeType.MACHINE,
                null, null, 1.0, 1.0, List.of(), null, null, 2);
        var rawC = new com.mervyn.miforeman.goal.RecipeGraphNode(rawCId, com.mervyn.miforeman.goal.NodeType.RAW,
                null, null, 1.0, 0.0, List.of(), null, null, 3);

        var edgeAToTarget = new com.mervyn.miforeman.goal.GraphEdge(machineAId, targetId, 1.0);
        var edgeBToA = new com.mervyn.miforeman.goal.GraphEdge(machineBId, machineAId, 1.0);
        var edgeCToB = new com.mervyn.miforeman.goal.GraphEdge(rawCId, machineBId, 1.0);
        target.putInput(edgeAToTarget);
        machineA.putOutput(edgeAToTarget);
        machineA.putInput(edgeBToA);
        machineB.putOutput(edgeBToA);
        machineB.putInput(edgeCToB);
        rawC.putOutput(edgeCToB);

        Map<ResourceLocation, com.mervyn.miforeman.goal.RecipeGraphNode> nodes = Map.of(
                targetId, target, machineAId, machineA, machineBId, machineB, rawCId, rawC);
        var graph = new com.mervyn.miforeman.goal.RecipeGraph(targetId, 1.0, nodes,
                List.of(edgeAToTarget, edgeBToA, edgeCToB), Set.of());

        var group = new com.mervyn.miforeman.goal.NodeGroup(java.util.UUID.randomUUID(), Set.of(machineBId, rawCId));
        Map<ResourceLocation, com.mervyn.miforeman.goal.NodePosition> currentPositions = Map.of(
                targetId, new com.mervyn.miforeman.goal.NodePosition(0, 0),
                machineAId, new com.mervyn.miforeman.goal.NodePosition(140, 0),
                machineBId, new com.mervyn.miforeman.goal.NodePosition(280, 0),
                rawCId, new com.mervyn.miforeman.goal.NodePosition(280, 50));

        int nodeW = 96, nodeH = 26, colGap = 44;
        var result1 = com.mervyn.miforeman.goal.GraphLayoutEngine.arrange(graph, List.of(group), currentPositions, true, nodeW, nodeH, colGap);

        var bAfter = result1.get(machineBId);
        var cAfter = result1.get(rawCId);
        if (bAfter == null || cAfter == null) {
            helper.fail("Group members missing from arrange result: " + result1);
            return;
        }

        int internalDx = cAfter.x() - bAfter.x();
        int expectedColSpacing = nodeW + colGap; // 140
        if (internalDx != expectedColSpacing) {
            helper.fail("Group interior member column layout failed: expected dx=" + expectedColSpacing
                    + " but got dx=" + internalDx + " (b=" + bAfter + ", c=" + cAfter + ")");
            return;
        }

        var result2 = com.mervyn.miforeman.goal.GraphLayoutEngine.arrange(graph, List.of(group), result1, true, nodeW, nodeH, colGap);
        if (!result1.equals(result2)) {
            helper.fail("GraphLayoutEngine.arrange with rearrangeInsideGroups is not idempotent across successive calls. First: "
                    + result1 + ", second: " + result2);
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGraphLayoutStateRemoveFromGroup(GameTestHelper helper) {
        var nodeA = ResourceLocation.parse("minecraft:iron_ingot");
        var nodeB = ResourceLocation.parse("minecraft:copper_ingot");
        var nodeC = ResourceLocation.parse("minecraft:gold_ingot");

        var group = new com.mervyn.miforeman.goal.NodeGroup(java.util.UUID.randomUUID(), Set.of(nodeA, nodeB, nodeC));
        var state = new com.mervyn.miforeman.goal.GraphLayoutState(
                Map.of(), List.of(), List.of(), Set.of(), List.of(group));

        var state2 = state.withNodeRemovedFromGroup(nodeA);
        if (state2.groups().size() != 1) {
            helper.fail("Expected 1 group remaining after removing 1 member from a 3-member group, got: " + state2.groups());
            return;
        }
        var remainingGroup = state2.groups().get(0);
        if (!remainingGroup.memberIds().equals(Set.of(nodeB, nodeC))) {
            helper.fail("Expected remaining group members to be {nodeB, nodeC}, got: " + remainingGroup.memberIds());
            return;
        }

        var state3 = state2.withNodeRemovedFromGroup(nodeB);
        if (!state3.groups().isEmpty()) {
            helper.fail("Expected group to dissolve when dropping below 2 members, but got: " + state3.groups());
            return;
        }

        var state4 = state3.withNodeRemovedFromGroup(nodeC);
        if (state4 != state3) {
            helper.fail("Removing un-grouped node should return identical state instance");
            return;
        }

        helper.succeed();
    }

    // Canvas geometry from GraphCanvas: 96x26 cards on a 140x40 pitch leave 44px corridors between columns and 14px between stacked cards.

    private static final int ROUTER_NODE_W = 96;
    private static final int ROUTER_NODE_H = 26;
    private static final int ROUTER_COL_SPACING = 140;
    private static final int ROUTER_ROW_SPACING = 40;

    private static com.mervyn.miforeman.goal.EdgeRouter.Obstacle routerCard(int column, int row) {
        int x = column * ROUTER_COL_SPACING;
        int y = row * ROUTER_ROW_SPACING;
        return new com.mervyn.miforeman.goal.EdgeRouter.Obstacle(x, y, x + ROUTER_NODE_W, y + ROUTER_NODE_H);
    }

    private static com.mervyn.miforeman.goal.EdgeRouter.Request routerWire(int fromCol, int fromRow, int toCol,
            int toRow) {
        return new com.mervyn.miforeman.goal.EdgeRouter.Request(
                fromCol * ROUTER_COL_SPACING + ROUTER_NODE_W, fromRow * ROUTER_ROW_SPACING + ROUTER_NODE_H / 2,
                toCol * ROUTER_COL_SPACING, toRow * ROUTER_ROW_SPACING + ROUTER_NODE_H / 2);
    }

    private static boolean routeClipsAnyCard(com.mervyn.miforeman.goal.EdgeRouter.Route route,
            List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> cards) {
        List<com.mervyn.miforeman.goal.EdgeRouter.Point> points = route.points();
        for (int i = 0; i < points.size() - 1; i++) {
            com.mervyn.miforeman.goal.EdgeRouter.Point a = points.get(i);
            com.mervyn.miforeman.goal.EdgeRouter.Point b = points.get(i + 1);
            int steps = Math.max(Math.abs(b.x() - a.x()), Math.abs(b.y() - a.y()));
            for (int s = 0; s <= steps; s++) {
                int x = steps == 0 ? a.x() : a.x() + (b.x() - a.x()) * s / steps;
                int y = steps == 0 ? a.y() : a.y() + (b.y() - a.y()) * s / steps;
                for (com.mervyn.miforeman.goal.EdgeRouter.Obstacle card : cards) {
                    if (x > card.minX() && x < card.maxX() && y > card.minY() && y < card.maxY())
                        return true;
                }
            }
        }
        return false;
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testEdgeRouterRoutesAroundBlockingCard(GameTestHelper helper) {
        List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> cards = List.of(
                routerCard(0, 0), routerCard(1, 0), routerCard(2, 0));
        com.mervyn.miforeman.goal.EdgeRouter.Request wire = routerWire(0, 0, 2, 0);

        if (!routeClipsAnyCard(com.mervyn.miforeman.goal.EdgeRouter.elbow(wire), cards)) {
            helper.fail("Baseline elbow was expected to cut through the middle card; "
                    + "this test proves nothing if it doesn't");
            return;
        }

        List<com.mervyn.miforeman.goal.EdgeRouter.Route> routes = com.mervyn.miforeman.goal.EdgeRouter
                .route(List.of(wire), cards, 6);
        com.mervyn.miforeman.goal.EdgeRouter.Route route = routes.get(0);
        if (route.fallback()) {
            helper.fail("Router fell back to an elbow on a routable wire: " + route.points());
            return;
        }
        if (routeClipsAnyCard(route, cards)) {
            helper.fail("Routed wire still clips a card: " + route.points());
            return;
        }
        helper.succeed();
    }

    /** Lane packing runs after the search, so an unchecked offset can slide a wire through a card. */
    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testEdgeRouterKeepsClearanceUnderLanePacking(GameTestHelper helper) {
        List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> cards = new java.util.ArrayList<>();
        for (int column = 0; column < 4; column++)
            for (int row = 0; row < 6; row++)
                cards.add(routerCard(column, row));

        List<com.mervyn.miforeman.goal.EdgeRouter.Request> wires = new java.util.ArrayList<>();
        for (int column = 0; column < 3; column++)
            for (int row = 0; row < 6; row++)
                wires.add(routerWire(column, row, column + 1, (row * 5 + column) % 6));

        List<com.mervyn.miforeman.goal.EdgeRouter.Route> routes = com.mervyn.miforeman.goal.EdgeRouter
                .route(wires, cards, 6, 3);
        for (int i = 0; i < routes.size(); i++) {
            com.mervyn.miforeman.goal.EdgeRouter.Route route = routes.get(i);
            if (route.fallback())
                continue; // A fallback elbow may clip.
            if (routeClipsAnyCard(route, cards)) {
                helper.fail("Wire " + i + " clips a card after lane packing: " + route.points());
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testEdgeRouterIsDeterministic(GameTestHelper helper) {
        List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> cards = new java.util.ArrayList<>();
        for (int column = 0; column < 3; column++)
            for (int row = 0; row < 4; row++)
                cards.add(routerCard(column, row));
        List<com.mervyn.miforeman.goal.EdgeRouter.Request> wires = List.of(
                routerWire(0, 0, 2, 3), routerWire(0, 3, 2, 0), routerWire(1, 1, 2, 2));

        List<com.mervyn.miforeman.goal.EdgeRouter.Route> first = com.mervyn.miforeman.goal.EdgeRouter
                .route(wires, cards, 6, 3);
        List<com.mervyn.miforeman.goal.EdgeRouter.Route> second = com.mervyn.miforeman.goal.EdgeRouter
                .route(wires, cards, 6, 3);
        if (!first.equals(second)) {
            helper.fail("Routing the same input twice gave different results:\n" + first + "\n" + second);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testEdgeRouterProducesCleanOrthogonalPolylines(GameTestHelper helper) {
        List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> cards = List.of(
                routerCard(0, 0), routerCard(1, 1), routerCard(2, 0), routerCard(1, 0));
        List<com.mervyn.miforeman.goal.EdgeRouter.Request> wires = List.of(
                routerWire(0, 0, 2, 0), routerWire(0, 0, 1, 1));

        List<com.mervyn.miforeman.goal.EdgeRouter.Route> routes = com.mervyn.miforeman.goal.EdgeRouter
                .route(wires, cards, 6, 3);
        for (int i = 0; i < routes.size(); i++) {
            com.mervyn.miforeman.goal.EdgeRouter.Route route = routes.get(i);
            List<com.mervyn.miforeman.goal.EdgeRouter.Point> points = route.points();
            com.mervyn.miforeman.goal.EdgeRouter.Request wire = wires.get(i);

            if (points.size() < 2) {
                helper.fail("Wire " + i + " produced a degenerate polyline: " + points);
                return;
            }
            com.mervyn.miforeman.goal.EdgeRouter.Point start = points.get(0);
            com.mervyn.miforeman.goal.EdgeRouter.Point end = points.get(points.size() - 1);
            if (start.x() != wire.fromX() || start.y() != wire.fromY()
                    || end.x() != wire.toX() || end.y() != wire.toY()) {
                helper.fail("Wire " + i + " does not meet its ports: " + points);
                return;
            }
            for (int p = 0; p < points.size() - 1; p++) {
                com.mervyn.miforeman.goal.EdgeRouter.Point a = points.get(p);
                com.mervyn.miforeman.goal.EdgeRouter.Point b = points.get(p + 1);
                if (a.equals(b)) {
                    helper.fail("Wire " + i + " has a zero-length segment at " + p + ": " + points);
                    return;
                }
                if (a.x() != b.x() && a.y() != b.y()) {
                    helper.fail("Wire " + i + " has a diagonal segment at " + p + ": " + points);
                    return;
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testEdgeRouterFallsBackWhenBoxedIn(GameTestHelper helper) {
        // A grid far too coarse for a 44px corridor leaves no legal cell beside a port.
        List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> cards = List.of(
                routerCard(0, 0), routerCard(1, 0), routerCard(2, 0));
        com.mervyn.miforeman.goal.EdgeRouter.Request wire = routerWire(0, 0, 2, 0);

        List<com.mervyn.miforeman.goal.EdgeRouter.Route> routes = com.mervyn.miforeman.goal.EdgeRouter
                .route(List.of(wire), cards, 40);
        com.mervyn.miforeman.goal.EdgeRouter.Route route = routes.get(0);
        if (!route.fallback()) {
            helper.fail("Expected a fallback on an unroutable wire, got a real route: " + route.points());
            return;
        }
        if (!route.points().equals(com.mervyn.miforeman.goal.EdgeRouter.elbow(wire).points())) {
            helper.fail("Fallback should be the plain elbow, got: " + route.points());
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testEdgeRouterPacksSharedLanesApart(GameTestHelper helper) {
        List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> cards = List.of(
                routerCard(0, 0), routerCard(0, 1), routerCard(2, 0), routerCard(2, 1), routerCard(1, 0));
        List<com.mervyn.miforeman.goal.EdgeRouter.Request> wires = List.of(
                routerWire(0, 0, 2, 0), routerWire(0, 1, 2, 1));

        List<com.mervyn.miforeman.goal.EdgeRouter.Route> packed = com.mervyn.miforeman.goal.EdgeRouter
                .route(wires, cards, 6, 4);
        if (packed.get(0).fallback() || packed.get(1).fallback()) {
            helper.fail("Neither wire should need a fallback here: " + packed);
            return;
        }
        if (packed.get(0).points().equals(packed.get(1).points())) {
            helper.fail("Two wires in the same corridor drew the identical polyline: " + packed.get(0).points());
            return;
        }
        for (com.mervyn.miforeman.goal.EdgeRouter.Route route : packed) {
            if (routeClipsAnyCard(route, cards)) {
                helper.fail("Lane packing pushed a wire into a card: " + route.points());
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testEdgeRouterRejectsNonPositiveGridSize(GameTestHelper helper) {
        try {
            com.mervyn.miforeman.goal.EdgeRouter.route(List.of(routerWire(0, 0, 1, 0)), List.of(), 0);
            helper.fail("Expected an IllegalArgumentException for gridSize 0");
            return;
        } catch (IllegalArgumentException expected) {
        }
        helper.succeed();
    }

    private static List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> routerCardsFor(
            Map<ResourceLocation, com.mervyn.miforeman.goal.NodePosition> positions) {
        List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> cards = new java.util.ArrayList<>();
        for (com.mervyn.miforeman.goal.NodePosition pos : positions.values())
            cards.add(new com.mervyn.miforeman.goal.EdgeRouter.Obstacle(
                    pos.x(), pos.y(), pos.x() + ROUTER_NODE_W, pos.y() + ROUTER_NODE_H));
        return cards;
    }

    private static List<com.mervyn.miforeman.goal.EdgeRouter.Request> routerWiresFor(
            com.mervyn.miforeman.goal.RecipeGraph graph,
            Map<ResourceLocation, com.mervyn.miforeman.goal.NodePosition> positions) {
        List<com.mervyn.miforeman.goal.EdgeRouter.Request> wires = new java.util.ArrayList<>();
        for (com.mervyn.miforeman.goal.GraphEdge edge : graph.edges()) {
            com.mervyn.miforeman.goal.NodePosition from = positions.get(edge.from());
            com.mervyn.miforeman.goal.NodePosition to = positions.get(edge.to());
            if (from == null || to == null)
                continue;
            // Most edges run right-to-left, since layout is target-first with inputs to the right.
            boolean leftward = to.x() < from.x();
            int fromX = leftward ? from.x() : from.x() + ROUTER_NODE_W;
            int toX = leftward ? to.x() + ROUTER_NODE_W : to.x();
            wires.add(new com.mervyn.miforeman.goal.EdgeRouter.Request(
                    fromX, from.y() + ROUTER_NODE_H / 2, toX, to.y() + ROUTER_NODE_H / 2));
        }
        return wires;
    }

    private static Map<ResourceLocation, com.mervyn.miforeman.goal.NodePosition> routerArrange(
            com.mervyn.miforeman.goal.RecipeGraph graph) {
        return com.mervyn.miforeman.goal.GraphLayoutEngine.arrange(graph, List.of(), Map.of(), false,
                ROUTER_NODE_W, ROUTER_NODE_H, ROUTER_COL_SPACING - ROUTER_NODE_W);
    }

    private static final int ROUTER_CHAMFER = 6;

    private static List<com.mervyn.miforeman.goal.PortLayout.Wire> routerPortWiresFor(
            com.mervyn.miforeman.goal.RecipeGraph graph,
            Map<ResourceLocation, com.mervyn.miforeman.goal.NodePosition> positions) {
        List<com.mervyn.miforeman.goal.PortLayout.Wire> wires = new java.util.ArrayList<>();
        for (com.mervyn.miforeman.goal.GraphEdge edge : graph.edges()) {
            com.mervyn.miforeman.goal.NodePosition from = positions.get(edge.from());
            com.mervyn.miforeman.goal.NodePosition to = positions.get(edge.to());
            if (from == null || to == null)
                continue;
            wires.add(new com.mervyn.miforeman.goal.PortLayout.Wire(edge.from(), edge.to(),
                    from.x(), from.y(), graph.node(edge.from()).getType() == com.mervyn.miforeman.goal.NodeType.MACHINE,
                    to.x(), to.y(), graph.node(edge.to()).getType() == com.mervyn.miforeman.goal.NodeType.MACHINE));
        }
        return wires;
    }

    private static List<com.mervyn.miforeman.goal.EdgeRouter.Request> routerRequestsWithPorts(
            List<com.mervyn.miforeman.goal.PortLayout.Wire> wires) {
        var ports = com.mervyn.miforeman.goal.PortLayout.assign(wires, ROUTER_NODE_H, ROUTER_CHAMFER);
        List<com.mervyn.miforeman.goal.EdgeRouter.Request> requests = new java.util.ArrayList<>();
        for (int i = 0; i < wires.size(); i++) {
            var w = wires.get(i);
            boolean leftward = w.toX() < w.fromX();
            int fromX = leftward ? w.fromX() : w.fromX() + ROUTER_NODE_W;
            int toX = leftward ? w.toX() + ROUTER_NODE_W : w.toX();
            requests.add(new com.mervyn.miforeman.goal.EdgeRouter.Request(
                    fromX, w.fromY() + ports.get(i).fromOffset(), toX, w.toY() + ports.get(i).toOffset()));
        }
        return requests;
    }

    private static com.mervyn.miforeman.goal.PortLayout.Wire portWire(String from, int fromX, int fromY, boolean fromMachine,
            String to, int toX, int toY, boolean toMachine) {
        return new com.mervyn.miforeman.goal.PortLayout.Wire(ResourceLocation.parse("miforeman_test:" + from),
                ResourceLocation.parse("miforeman_test:" + to), fromX, fromY, fromMachine, toX, toY, toMachine);
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testPortLayoutSpreadsSharedFaces(GameTestHelper helper) {
        // Fan-out of three from a resource card to targets on its left, listed out of y order.
        List<com.mervyn.miforeman.goal.PortLayout.Wire> fan = List.of(
                portWire("src", 280, 40, false, "low", 0, 80, true),
                portWire("src", 280, 40, false, "top", 0, 0, true),
                portWire("src", 280, 40, false, "mid", 0, 40, true));
        var fanPorts = com.mervyn.miforeman.goal.PortLayout.assign(fan, ROUTER_NODE_H, ROUTER_CHAMFER);
        int top = fanPorts.get(1).fromOffset(), mid = fanPorts.get(2).fromOffset(), low = fanPorts.get(0).fromOffset();
        if (!(top < mid && mid < low)) {
            helper.fail("A fan-out should leave in the order of its targets' heights, got top=" + top + " mid=" + mid + " low=" + low);
            return;
        }
        if (mid - top < com.mervyn.miforeman.goal.PortLayout.MIN_SPACING || low - mid < com.mervyn.miforeman.goal.PortLayout.MIN_SPACING) {
            helper.fail("Fan-out ports closer than the minimum spacing: " + top + ", " + mid + ", " + low);
            return;
        }
        // Each target receives one wire, so its port stays at the face centre.
        if (fanPorts.get(0).toOffset() != ROUTER_NODE_H / 2) {
            helper.fail("A lone wire on a face should attach at the centre, got " + fanPorts.get(0).toOffset());
            return;
        }

        // A machine card's corners are cut, so its ports stay inside the uncut band. Past capacity, neighbouring wires share a slot in order.
        List<com.mervyn.miforeman.goal.PortLayout.Wire> machineFan = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++)
            machineFan.add(portWire("m", 280, 0, true, "t" + i, 0, i * 40, false));
        var machinePorts = com.mervyn.miforeman.goal.PortLayout.assign(machineFan, ROUTER_NODE_H, ROUTER_CHAMFER);
        java.util.TreeSet<Integer> distinct = new java.util.TreeSet<>();
        int previous = Integer.MIN_VALUE;
        for (var port : machinePorts) {
            int y = port.fromOffset();
            if (y - 1 < ROUTER_CHAMFER || y + 1 > ROUTER_NODE_H - ROUTER_CHAMFER) {
                helper.fail("A machine port at offset " + y + " runs into the card's cut corner.");
                return;
            }
            if (y < previous) {
                helper.fail("Overflowing ports must keep their order, got " + machinePorts);
                return;
            }
            previous = y;
            distinct.add(y);
        }
        Integer last = null;
        for (int y : distinct) {
            if (last != null && y - last < com.mervyn.miforeman.goal.PortLayout.MIN_SPACING) {
                helper.fail("Shared machine slots closer than the minimum spacing: " + distinct);
                return;
            }
            last = y;
        }

        // A card's in-ports (right face, here) and out-ports (left face) are laid out separately.
        List<com.mervyn.miforeman.goal.PortLayout.Wire> through = List.of(
                portWire("up", 420, 0, false, "x", 140, 0, false),
                portWire("x", 140, 0, false, "down", 0, 0, false));
        var throughPorts = com.mervyn.miforeman.goal.PortLayout.assign(through, ROUTER_NODE_H, ROUTER_CHAMFER);
        if (throughPorts.get(0).toOffset() != ROUTER_NODE_H / 2 || throughPorts.get(1).fromOffset() != ROUTER_NODE_H / 2) {
            helper.fail("One wire in and one wire out on different faces should both stay centred: " + throughPorts);
            return;
        }

        if (!com.mervyn.miforeman.goal.PortLayout.assign(machineFan, ROUTER_NODE_H, ROUTER_CHAMFER).equals(machinePorts)) {
            helper.fail("PortLayout must be deterministic.");
            return;
        }
        helper.succeed();
    }

    private static Map<ResourceLocation, com.mervyn.miforeman.goal.NodePosition> routerMovedBelow(
            Map<ResourceLocation, com.mervyn.miforeman.goal.NodePosition> positions, ResourceLocation node, int slot) {
        int maxY = positions.values().stream().mapToInt(com.mervyn.miforeman.goal.NodePosition::y).max().orElse(0);
        Map<ResourceLocation, com.mervyn.miforeman.goal.NodePosition> moved = new java.util.HashMap<>(positions);
        moved.put(node, new com.mervyn.miforeman.goal.NodePosition(positions.get(node).x(), maxY + 120 * (slot + 1)));
        return moved;
    }

    private static boolean routerTouches(com.mervyn.miforeman.goal.PortLayout.Wire wire, ResourceLocation node) {
        return wire.from().equals(node) || wire.to().equals(node);
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID, timeoutTicks = 600)
    public static void testEdgeRouterReusesWiresAcrossPlanChanges(GameTestHelper helper) {
        var level = helper.getLevel();
        com.mervyn.miforeman.goal.RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level,
                new ProductionGoal("router_plan_change", ProductionGoal.TargetType.ITEM,
                        ResourceLocation.parse("modern_industrialization:analog_circuit"), 60.0));
        var positions = routerArrange(graph);
        var requests = routerRequestsWithPorts(routerPortWiresFor(graph, positions));
        var cards = routerCardsFor(positions);
        var full = com.mervyn.miforeman.goal.EdgeRouter.route(requests, cards, 6, 3, null);

        List<com.mervyn.miforeman.goal.EdgeRouter.Request> changed = new java.util.ArrayList<>();
        for (int i = requests.size() - 1; i >= 0; i--)
            if (i % 7 != 0)
                changed.add(requests.get(i));
        var reused = com.mervyn.miforeman.goal.EdgeRouter.route(changed, cards, 6, 3, full);
        var fresh = com.mervyn.miforeman.goal.EdgeRouter.route(changed, cards, 6, 3, null);

        Map<com.mervyn.miforeman.goal.EdgeRouter.Request, Integer> before = new java.util.HashMap<>();
        for (int i = 0; i < requests.size(); i++)
            before.merge(requests.get(i), i, (a, b) -> -1);
        int kept = 0;
        for (int i = 0; i < changed.size(); i++) {
            Integer was = before.get(changed.get(i));
            if (was == null || was < 0 || full.fallbacks().get(was))
                continue;
            if (!reused.unpacked().get(i).equals(full.unpacked().get(was))) {
                helper.fail("Wire " + i + " kept its endpoints across the plan change but was re-routed.");
                return;
            }
            kept++;
        }
        if (kept < changed.size() / 2) {
            helper.fail("Only " + kept + " of " + changed.size() + " wires kept their route.");
            return;
        }
        if (reused.expansions() * 4 > fresh.expansions()) {
            helper.fail("Re-routing after a plan change spent " + reused.expansions() + " expansions against "
                    + fresh.expansions() + " from scratch; it isn't reusing wires.");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID, timeoutTicks = 600)
    public static void testEdgeRouterIncrementalMatchesContract(GameTestHelper helper) {
        var level = helper.getLevel();
        com.mervyn.miforeman.goal.RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level,
                new ProductionGoal("router_incremental", ProductionGoal.TargetType.ITEM,
                        ResourceLocation.parse("modern_industrialization:analog_circuit"), 60.0));
        var positions = routerArrange(graph);
        var wires = routerPortWiresFor(graph, positions);
        var requests = routerRequestsWithPorts(wires);
        var full = com.mervyn.miforeman.goal.EdgeRouter.route(requests, routerCardsFor(positions), 6, 3, null);

        ResourceLocation moved = wires.get(0).from();
        var movedPositions = routerMovedBelow(positions, moved, 0);
        var movedWires = routerPortWiresFor(graph, movedPositions);
        var movedRequests = routerRequestsWithPorts(movedWires);
        var movedCards = routerCardsFor(movedPositions);
        var incremental = com.mervyn.miforeman.goal.EdgeRouter.route(movedRequests, movedCards, 6, 3, full);
        var fresh = com.mervyn.miforeman.goal.EdgeRouter.route(movedRequests, movedCards, 6, 3, null);

        if (incremental.routes().size() != movedRequests.size()) {
            helper.fail("Incremental routing returned " + incremental.routes().size() + " routes for " + movedRequests.size() + " wires.");
            return;
        }
        boolean movedWireChanged = false;
        for (int i = 0; i < movedRequests.size(); i++) {
            boolean sameRequest = movedRequests.get(i).equals(requests.get(i));
            if (routerTouches(movedWires.get(i), moved) && !sameRequest)
                movedWireChanged = true;
            if (sameRequest && !full.fallbacks().get(i) && !incremental.unpacked().get(i).equals(full.unpacked().get(i))) {
                helper.fail("Wire " + i + " didn't move and nothing landed on it, but it was re-routed.");
                return;
            }
            if (!incremental.fallbacks().get(i) && routeClipsAnyCard(incremental.routes().get(i), movedCards)) {
                helper.fail("Wire " + i + " clips a card after an incremental re-route: " + incremental.routes().get(i).points());
                return;
            }
        }
        if (!movedWireChanged) {
            helper.fail("Moving a card should have changed at least one of its wires' requests.");
            return;
        }
        if (incremental.expansions() * 4 > fresh.expansions()) {
            helper.fail("Incremental re-route spent " + incremental.expansions() + " expansions against "
                    + fresh.expansions() + " for a full pass; it isn't saving work.");
            return;
        }

        // A card dropped right beside a port covers that wire's stub: the wire must not be reused.
        var cardA = routerCard(0, 0);
        var cardB = routerCard(2, 0);
        var wire = routerWire(0, 0, 2, 0);
        var before = com.mervyn.miforeman.goal.EdgeRouter.route(List.of(wire), List.of(cardA, cardB), 6, 3, null);
        var onStub = new com.mervyn.miforeman.goal.EdgeRouter.Obstacle(ROUTER_NODE_W + 2, 6, ROUTER_NODE_W + 14, 20);
        if (!routeClipsAnyCard(before.routes().get(0), List.of(onStub))) {
            helper.fail("Test setup: the original wire should run through the card dropped on its stub.");
            return;
        }
        var after = com.mervyn.miforeman.goal.EdgeRouter.route(List.of(wire), List.of(cardA, cardB, onStub), 6, 3, before);
        if (!after.fallbacks().get(0) && after.unpacked().get(0).equals(before.unpacked().get(0))) {
            helper.fail("A wire whose stub a new card now covers was reused unchanged.");
            return;
        }

        var middle = routerCard(1, 0);
        var hidden = com.mervyn.miforeman.goal.EdgeRouter.route(List.of(wire), List.of(cardA, cardB), 6, 3, null);
        var shown = com.mervyn.miforeman.goal.EdgeRouter.route(List.of(wire), List.of(cardA, cardB, middle), 6, 3, hidden);
        if (!shown.fallbacks().get(0) && routeClipsAnyCard(shown.routes().get(0), List.of(middle))) {
            helper.fail("A wire kept running through a card that was unhidden on its path: " + shown.routes().get(0).points());
            return;
        }

        List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> walled = new java.util.ArrayList<>(List.of(cardA, cardB));
        for (int row = -10; row <= 10; row++)
            walled.add(routerCard(1, row));
        var blocked = com.mervyn.miforeman.goal.EdgeRouter.route(List.of(wire), walled, 6, 3, null);
        if (!blocked.fallbacks().get(0)) {
            helper.fail("Test setup: the walled-in wire should have fallen back.");
            return;
        }
        List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> opened = new java.util.ArrayList<>(walled);
        opened.remove(routerCard(1, 0));
        opened.add(routerCard(1, 40));
        var unblocked = com.mervyn.miforeman.goal.EdgeRouter.route(List.of(wire), opened, 6, 3, blocked);
        if (unblocked.fallbacks().get(0)) {
            helper.fail("Moving the card out of a fallback's way should let it route.");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID, timeoutTicks = 600)
    public static void testEdgeRouterIncrementalDoesNotDrift(GameTestHelper helper) {
        var level = helper.getLevel();
        com.mervyn.miforeman.goal.RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level,
                new ProductionGoal("router_drift", ProductionGoal.TargetType.ITEM,
                        ResourceLocation.parse("modern_industrialization:analog_circuit"), 60.0));
        var positions = routerArrange(graph);
        var wires = routerPortWiresFor(graph, positions);
        var routing = com.mervyn.miforeman.goal.EdgeRouter.route(routerRequestsWithPorts(wires),
                routerCardsFor(positions), 6, 3, null);

        List<ResourceLocation> toMove = wires.stream().map(com.mervyn.miforeman.goal.PortLayout.Wire::from)
                .distinct().limit(5).toList();
        for (int step = 0; step < toMove.size(); step++) {
            positions = routerMovedBelow(positions, toMove.get(step), step);
            var requests = routerRequestsWithPorts(routerPortWiresFor(graph, positions));
            var cards = routerCardsFor(positions);
            routing = com.mervyn.miforeman.goal.EdgeRouter.route(requests, cards, 6, 3, routing);

            if (routing.routes().size() != requests.size()) {
                helper.fail("Move " + step + ": " + routing.routes().size() + " routes for " + requests.size() + " wires.");
                return;
            }
            int routed = 0;
            for (int i = 0; i < requests.size(); i++) {
                var points = routing.routes().get(i).points();
                var request = requests.get(i);
                if (!points.get(0).equals(new com.mervyn.miforeman.goal.EdgeRouter.Point(request.fromX(), request.fromY()))
                        || !points.get(points.size() - 1).equals(new com.mervyn.miforeman.goal.EdgeRouter.Point(request.toX(), request.toY()))) {
                    helper.fail("Move " + step + ": wire " + i + " no longer ends at its ports: " + points);
                    return;
                }
                if (routing.fallbacks().get(i))
                    continue;
                routed++;
                if (routeClipsAnyCard(routing.routes().get(i), cards)) {
                    helper.fail("Move " + step + ": wire " + i + " clips a card: " + points);
                    return;
                }
            }
            if (routed * 2 < requests.size()) {
                helper.fail("Move " + step + ": only " + routed + " of " + requests.size() + " wires still route.");
                return;
            }
        }
        helper.succeed();
    }

    /** A wire with 10px port stubs at both ends around one interior segment from (x1,y1) to (x2,y2). */
    private static List<com.mervyn.miforeman.goal.EdgeRouter.Point> hopWire(int x1, int y1, int x2, int y2) {
        boolean horizontal = y1 == y2;
        int sx = horizontal ? Integer.signum(x2 - x1) * 10 : 0;
        int sy = horizontal ? 0 : Integer.signum(y2 - y1) * 10;
        return List.of(new com.mervyn.miforeman.goal.EdgeRouter.Point(x1 - sx, y1 - sy),
                new com.mervyn.miforeman.goal.EdgeRouter.Point(x1, y1),
                new com.mervyn.miforeman.goal.EdgeRouter.Point(x2, y2),
                new com.mervyn.miforeman.goal.EdgeRouter.Point(x2 + sx, y2 + sy));
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testEdgeRouterHopsOncePerCrossing(GameTestHelper helper) {
        var across = hopWire(10, 50, 200, 50);
        var down = hopWire(100, 10, 100, 150);

        // Only the wire drawn later bumps.
        var downOnTop = com.mervyn.miforeman.goal.EdgeRouter.hops(List.of(across, down), 5, 3);
        if (!downOnTop.get(0).isEmpty() || !downOnTop.get(1).equals(List.of(new com.mervyn.miforeman.goal.EdgeRouter.Hop(1, 47, 53)))) {
            helper.fail("Expected only the later (vertical) wire to bump at y=50, got " + downOnTop);
            return;
        }
        var acrossOnTop = com.mervyn.miforeman.goal.EdgeRouter.hops(List.of(down, across), 5, 3);
        if (!acrossOnTop.get(0).isEmpty() || !acrossOnTop.get(1).equals(List.of(new com.mervyn.miforeman.goal.EdgeRouter.Hop(1, 97, 103)))) {
            helper.fail("Expected only the later (horizontal) wire to bump at x=100, got " + acrossOnTop);
            return;
        }

        // No bump near either segment's end, on a stub, or on a parallel overlap.
        var nearCorner = hopWire(12, 0, 12, 120);
        var endsAtCrossing = hopWire(150, 0, 150, 52);
        var acrossStub = hopWire(5, 0, 5, 120);
        var parallel = hopWire(40, 50, 160, 50);
        var none = com.mervyn.miforeman.goal.EdgeRouter.hops(List.of(nearCorner, endsAtCrossing, acrossStub, parallel, across), 5, 3);
        if (!none.get(4).isEmpty()) {
            helper.fail("Bumps near corners, on stubs or on parallel overlaps: " + none.get(4));
            return;
        }

        // The port stub merges into a wire's first run. A crossing along that run still bumps, only one right by the port doesn't.
        var firstRun = List.of(new com.mervyn.miforeman.goal.EdgeRouter.Point(0, 50),
                new com.mervyn.miforeman.goal.EdgeRouter.Point(200, 50),
                new com.mervyn.miforeman.goal.EdgeRouter.Point(200, 120),
                new com.mervyn.miforeman.goal.EdgeRouter.Point(210, 120));
        var runHops = com.mervyn.miforeman.goal.EdgeRouter.hops(
                List.of(hopWire(100, 10, 100, 150), hopWire(8, 10, 8, 150), firstRun), 5, 3);
        if (!runHops.get(2).equals(List.of(new com.mervyn.miforeman.goal.EdgeRouter.Hop(0, 97, 103)))) {
            helper.fail("A crossing along a wire's first run should bump, one by its port shouldn't, got " + runHops.get(2));
            return;
        }

        // Crossings closer than one bump merge into a single span.
        var bundle = com.mervyn.miforeman.goal.EdgeRouter.hops(
                List.of(hopWire(100, 10, 100, 150), hopWire(104, 10, 104, 150), across), 5, 3);
        if (!bundle.get(2).equals(List.of(new com.mervyn.miforeman.goal.EdgeRouter.Hop(1, 97, 107)))) {
            helper.fail("Two crossings 4px apart should merge into one bump, got " + bundle.get(2));
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID, timeoutTicks = 600)
    public static void testEdgeRouterHopsStayInsideSegments(GameTestHelper helper) {
        var level = helper.getLevel();
        com.mervyn.miforeman.goal.RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level,
                new ProductionGoal("router_hops", ProductionGoal.TargetType.ITEM,
                        ResourceLocation.parse("modern_industrialization:analog_circuit"), 60.0));
        var positions = routerArrange(graph);
        var routes = com.mervyn.miforeman.goal.EdgeRouter.route(
                routerRequestsWithPorts(routerPortWiresFor(graph, positions)), routerCardsFor(positions), 6, 3);
        List<List<com.mervyn.miforeman.goal.EdgeRouter.Point>> polylines = routes.stream()
                .map(com.mervyn.miforeman.goal.EdgeRouter.Route::points).toList();
        var hops = com.mervyn.miforeman.goal.EdgeRouter.hops(polylines, 5, 3);

        int total = 0;
        for (int w = 0; w < polylines.size(); w++) {
            var points = polylines.get(w);
            for (var hop : hops.get(w)) {
                total++;
                if (hop.segment() < 0 || hop.segment() > points.size() - 2) {
                    helper.fail("Wire " + w + " has a bump on a segment it doesn't have: " + hop);
                    return;
                }
                var a = points.get(hop.segment());
                var b = points.get(hop.segment() + 1);
                int min = a.y() == b.y() ? Math.min(a.x(), b.x()) : Math.min(a.y(), b.y());
                int max = a.y() == b.y() ? Math.max(a.x(), b.x()) : Math.max(a.y(), b.y());
                var first = points.get(0);
                var end = points.get(points.size() - 1);
                for (var port : List.of(first, end)) {
                    int along = a.y() == b.y() ? port.x() : port.y();
                    boolean onLine = a.y() == b.y() ? port.y() == a.y() : port.x() == a.x();
                    if (onLine && along >= min && along <= max
                            && (Math.abs(hop.from() - along) < 9 || Math.abs(hop.to() - along) < 9)) {
                        helper.fail("Wire " + w + "'s bump " + hop + " sits on the port stub at " + port + ".");
                        return;
                    }
                }
                if (hop.from() <= min || hop.to() >= max || hop.from() >= hop.to()) {
                    helper.fail("Wire " + w + "'s bump " + hop + " runs past its segment [" + min + ", " + max + "].");
                    return;
                }
            }
        }
        if (total == 0) {
            helper.fail("A real routed graph should have at least one crossing to bump.");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID, timeoutTicks = 600)
    public static void testEdgeRouterOnRealGraphWithSpreadPorts(GameTestHelper helper) {
        var level = helper.getLevel();
        com.mervyn.miforeman.goal.RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level,
                new ProductionGoal("router_ports", ProductionGoal.TargetType.ITEM,
                        ResourceLocation.parse("modern_industrialization:analog_circuit"), 60.0));
        Map<ResourceLocation, com.mervyn.miforeman.goal.NodePosition> positions = routerArrange(graph);
        List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> cards = routerCardsFor(positions);
        var wires = routerPortWiresFor(graph, positions);
        var requests = routerRequestsWithPorts(wires);
        var routes = com.mervyn.miforeman.goal.EdgeRouter.route(requests, cards, 6, 3);

        int routed = 0;
        for (int i = 0; i < routes.size(); i++) {
            if (routes.get(i).fallback())
                continue;
            routed++;
            if (routeClipsAnyCard(routes.get(i), cards)) {
                helper.fail("Wire " + i + " clips a card with spread ports: " + routes.get(i).points());
                return;
            }
        }
        if (routed * 2 < routes.size()) {
            helper.fail("Only " + routed + " of " + routes.size() + " wires routed with spread ports.");
            return;
        }

        // Two wires may only share a start point when their face is over capacity.
        Map<String, Integer> faceSize = new java.util.HashMap<>();
        for (var w : wires)
            faceSize.merge(w.from() + (w.toX() < w.fromX() ? ":L" : ":R"), 1, Integer::sum);
        Map<String, Integer> startUses = new java.util.HashMap<>();
        for (var r : requests)
            startUses.merge(r.fromX() + "," + r.fromY(), 1, Integer::sum);
        for (int i = 0; i < wires.size(); i++) {
            var w = wires.get(i);
            var r = requests.get(i);
            int capacity = w.fromMachine() ? 4 : 5;
            int size = faceSize.get(w.from() + (w.toX() < w.fromX() ? ":L" : ":R"));
            if (startUses.get(r.fromX() + "," + r.fromY()) > 1 && size <= capacity) {
                helper.fail("Wire " + i + " shares its start point although its face (" + size + " wires) has free slots.");
                return;
            }
        }
        helper.succeed();
    }

    /** analog_circuit is realistic but fits in MAX_TOTAL_EXPANSIONS. iron_plate is mostly the ingot and nugget 2-cycle, too small to test layout. Some fallbacks are fine, so this asserts most wires route. */
    @GameTest(template = "empty", templateNamespace = MIForeman.MODID, timeoutTicks = 600)
    public static void testEdgeRouterOnRealArrangedGraph(GameTestHelper helper) {
        var level = helper.getLevel();
        ProductionGoal goal = new ProductionGoal("router_graph", ProductionGoal.TargetType.ITEM,
                ResourceLocation.parse("modern_industrialization:analog_circuit"), 60.0);

        com.mervyn.miforeman.goal.RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        if (graph == null || graph.nodes().isEmpty()) {
            helper.fail("Expected a non-empty recipe graph for analog_circuit");
            return;
        }
        Map<ResourceLocation, com.mervyn.miforeman.goal.NodePosition> positions = routerArrange(graph);
        List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> cards = routerCardsFor(positions);
        List<com.mervyn.miforeman.goal.EdgeRouter.Request> wires = routerWiresFor(graph, positions);
        if (wires.isEmpty()) {
            helper.fail("Expected the arranged analog_circuit graph to produce routable edges");
            return;
        }

        List<com.mervyn.miforeman.goal.EdgeRouter.Route> routes = com.mervyn.miforeman.goal.EdgeRouter
                .route(wires, cards, 6, 3);
        int routed = 0;
        for (int i = 0; i < routes.size(); i++) {
            com.mervyn.miforeman.goal.EdgeRouter.Route route = routes.get(i);
            if (route.fallback())
                continue;
            routed++;
            if (routeClipsAnyCard(route, cards)) {
                helper.fail("Wire " + i + " of the real arranged graph clips a card: " + route.points());
                return;
            }
        }
        // Measured at 130 of 154. Half is a floor that catches the router falling back on everything.
        if (routed * 2 < routes.size()) {
            helper.fail("Only " + routed + " of " + routes.size() + " wires routed on a real graph; "
                    + "the router is effectively inert at this size");
            return;
        }
        helper.succeed();
    }

    /** Routing only the first wires and elbowing the rest would look like a rendering bug, so a pass out of budget drops the whole graph. */
    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testEdgeRouterGivesUpWholeGraphWhenBudgetExhausted(GameTestHelper helper) {
        List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> cards = new java.util.ArrayList<>();
        for (int column = 0; column < 4; column++)
            for (int row = 0; row < 6; row++)
                cards.add(routerCard(column, row));
        List<com.mervyn.miforeman.goal.EdgeRouter.Request> wires = new java.util.ArrayList<>();
        for (int column = 0; column < 3; column++)
            for (int row = 0; row < 6; row++)
                wires.add(routerWire(column, row, column + 1, (row * 5 + column) % 6));

        List<com.mervyn.miforeman.goal.EdgeRouter.Route> routes = com.mervyn.miforeman.goal.EdgeRouter
                .route(wires, cards, 6, 3, 200);
        for (int i = 0; i < routes.size(); i++) {
            if (!routes.get(i).fallback()) {
                helper.fail("Wire " + i + " routed while others fell back; a partially routed "
                        + "graph is what the whole-pass budget exists to prevent");
                return;
            }
        }

        // The normal budget must route this graph, or the check above proves nothing.
        List<com.mervyn.miforeman.goal.EdgeRouter.Route> generous = com.mervyn.miforeman.goal.EdgeRouter
                .route(wires, cards, 6, 3);
        if (generous.stream().allMatch(com.mervyn.miforeman.goal.EdgeRouter.Route::fallback)) {
            helper.fail("This graph should route comfortably within the default budget");
            return;
        }
        helper.succeed();
    }

    /** Timing isn't asserted, as it would flake on CI. MAX_TOTAL_EXPANSIONS bounds the work, so a slow regression shows up as wholesale fallback. */
    @GameTest(template = "empty", templateNamespace = MIForeman.MODID, timeoutTicks = 600)
    public static void testEdgeRouterHandlesLargestRealGraph(GameTestHelper helper) {
        var level = helper.getLevel();
        ProductionGoal goal = new ProductionGoal("router_largest", ProductionGoal.TargetType.ITEM,
                ResourceLocation.parse("modern_industrialization:quantum_upgrade"), 1.0);

        com.mervyn.miforeman.goal.RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        if (graph == null || graph.nodes().size() < 100) {
            helper.fail("Expected a large recipe graph for quantum_upgrade, got "
                    + (graph == null ? "null" : graph.nodes().size() + " nodes"));
            return;
        }
        Map<ResourceLocation, com.mervyn.miforeman.goal.NodePosition> positions = routerArrange(graph);
        List<com.mervyn.miforeman.goal.EdgeRouter.Obstacle> cards = routerCardsFor(positions);
        List<com.mervyn.miforeman.goal.EdgeRouter.Request> wires = routerWiresFor(graph, positions);

        long start = System.nanoTime();
        List<com.mervyn.miforeman.goal.EdgeRouter.Route> routes = com.mervyn.miforeman.goal.EdgeRouter
                .route(wires, cards, 6, 3);
        long elapsedMs = (System.nanoTime() - start) / 1000000L;

        int routed = 0;
        for (int i = 0; i < routes.size(); i++) {
            com.mervyn.miforeman.goal.EdgeRouter.Route route = routes.get(i);
            if (route.fallback())
                continue;
            routed++;
            if (routeClipsAnyCard(route, cards)) {
                helper.fail("Wire " + i + " of the largest real graph clips a card: " + route.points());
                return;
            }
        }
        MIForeman.LOGGER.info("EdgeRouter largest real graph: {} cards, {} wires, {} routed, {}ms",
                cards.size(), routes.size(), routed, elapsedMs);
        // Measured at 707 of 864. Half is a floor.
        if (routed * 2 < routes.size()) {
            helper.fail("Only " + routed + " of " + routes.size() + " wires routed on the largest "
                    + "real graph; the router is effectively inert at this size");
            return;
        }
        helper.succeed();
    }
}
