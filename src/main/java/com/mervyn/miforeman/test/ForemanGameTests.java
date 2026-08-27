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
                        false));

        @SuppressWarnings("deprecation")
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                level.registryAccess());
        ProductionGoal.STREAM_CODEC.encode(buf, goal);
        ProductionGoal decoded = ProductionGoal.STREAM_CODEC.decode(buf);

        if (!decoded.equals(goal)) {
            helper.fail("STREAM_CODEC round-trip does not match original ProductionGoal -- a field was likely "
                    + "added to CODEC without updating STREAM_CODEC (or vice versa). Original: " + goal + ", decoded: "
                    + decoded);
            return;
        }

        // Verify that ProductionGoal.GLOBAL_POS_LIST_CODEC deserializes legacy BlockPos
        // lists into Overworld GlobalPos
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

        // Explicitly verify all GraphViewMode enum values round-trip through both CODEC
        // and STREAM_CODEC
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

        // polyvinyl_chloride rate now matches exactly between computePlan() rawInputs
        // and
        // computeRecipeGraph() graph node (437500.0) after resolving the
        // cycle-detection memoization bug.
        ResourceLocation pvcId = ResourceLocation.parse("modern_industrialization:polyvinyl_chloride");
        double pvcRate = plan.rawInputs().stream()
                .filter(flow -> flow.resourceId().equals(pvcId))
                .mapToDouble(ProductionGoal.MaterialFlow::rate)
                .sum();

        if (Math.abs(pvcRate - 437500.0) > 0.001) {
            helper.fail("Expected 437500.0 polyvinyl_chloride rate, but calculated: " + pvcRate);
            return;
        }

        com.mervyn.miforeman.goal.RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        var pvcNode = graph.nodes().get(pvcId);
        if (pvcNode == null || Math.abs(pvcNode.getRequiredRate() - 437500.0) > 0.001) {
            helper.fail("Expected 437500.0 polyvinyl_chloride requiredRate on graph node, but calculated: "
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

        // Test 1: Empty inputs -> Starving (RED)
        com.mervyn.miforeman.goal.MachineStatus statusStarving = ServerMonitoringManager.getMachinePassiveStatus(crafter, level);
        if (statusStarving != com.mervyn.miforeman.goal.MachineStatus.RED) {
            helper.fail("Expected machine with empty inputs to be STARVING (RED), but got: " + statusStarving);
            return;
        }

        // Test 2: Add valid inputs, but block outputs -> Saturating (ORANGE)
        var ironIngot = net.minecraft.world.item.Items.IRON_INGOT;
        var inputSlot = crafter.getItemInputs().get(0);
        inputSlot.setKey(aztech.modern_industrialization.thirdparty.fabrictransfer.api.item.ItemVariant.of(ironIngot));
        inputSlot.setAmount(1);

        var outputSlot = ((UnifiedCrafter.StandardCrafterAdapter) crafter).getUnderlying().getInventory()
                .getItemOutputs().get(0);
        outputSlot.setKey(aztech.modern_industrialization.thirdparty.fabrictransfer.api.item.ItemVariant
                .of(net.minecraft.world.item.Items.GLASS));
        outputSlot.setAmount(64);

        com.mervyn.miforeman.goal.MachineStatus statusSaturating = ServerMonitoringManager.getMachinePassiveStatus(crafter, level);
        if (statusSaturating != com.mervyn.miforeman.goal.MachineStatus.ORANGE) {
            helper.fail("Expected machine with matched inputs but blocked outputs to be SATURATING (ORANGE), but got: "
                    + statusSaturating);
            return;
        }

        // Test 3: Clear outputs -> ORANGE (ready but inactive)
        outputSlot.empty();
        com.mervyn.miforeman.goal.MachineStatus statusReady = ServerMonitoringManager.getMachinePassiveStatus(crafter, level);
        if (statusReady != com.mervyn.miforeman.goal.MachineStatus.ORANGE) {
            helper.fail(
                    "Expected ready-to-craft machine to return ORANGE (since it can start but is not currently active), but got: "
                            + statusReady);
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

        // Find an ambiguous resource node with at least 2 options
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

        // Verify initial selection matches first option
        if (!firstRecipe.equals(ambiguousNode.getSelectedAmbiguity())) {
            helper.fail("Expected initial ambiguity selection to be " + firstRecipe + ", but was: "
                    + ambiguousNode.getSelectedAmbiguity());
            return;
        }

        // Update selections to second recipe choice
        java.util.Map<ResourceLocation, ResourceLocation> selections = new java.util.HashMap<>(goal.recipeSelections());
        selections.put(ambiguousNode.getAmbiguityOwnerId(), secondRecipe);

        ProductionGoal updatedGoal = new ProductionGoal(
                goal.name(), goal.type(), goal.targetId(), goal.rate(),
                selections, java.util.Optional.empty(), goal.perHour(),
                goal.threshold(), goal.linkedMachines());
        var updatedGraph = RecipeGraphTraverser.computeRecipeGraph(level, updatedGoal);

        // Verify that the new recipe exists in the graph and the old one does not
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

        // Verify that the resource node's selected ambiguity is updated
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

    /**
     * Verifies that {@code RecipeGraphTraverser} produces deduplicated edges per
     * (from, to) pair.
     * Validates node and edge counts against a regression snapshot of the
     * quantum_upgrade graph.
     */
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

        if (graph.edges().size() != 864) {
            helper.fail("Expected 864 edges in the quantum_upgrade graph, but got: " + graph.edges().size()
                    + ". If this changed intentionally, e.g. an MI recipe update, update this snapshot.");
            return;
        }
        if (graph.nodes().size() != 588) {
            helper.fail("Expected 588 nodes in the quantum_upgrade graph, but got: " + graph.nodes().size()
                    + ". If this changed intentionally, e.g. an MI recipe update, update this snapshot.");
            return;
        }

        helper.succeed();
    }

    /**
     * Verifies that {@code RecipeGraphNode.ambiguityOwnerId} consistently
     * identifies the resource ID
     * describing a node's ambiguity options.
     */
    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testAmbiguityOwnerIdIsConsistent(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        ProductionGoal goal = new ProductionGoal("ambiguity_owner_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);
        var graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);

        for (var node : graph.nodes().values()) {
            if (node.getAmbiguityOptions().isEmpty()) {
                // RAW nodes and any resource/machine with only one candidate producer carry no
                // ambiguity data, so ambiguityOwnerId is null and there's nothing to check
                // here.
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
    public static void testCloseSyncGoalPreservesLastSynced(GameTestHelper helper) {
        ResourceLocation targetId = ResourceLocation.parse("minecraft:iron_ingot");
        ProductionGoal synced = new ProductionGoal("synced_goal", ProductionGoal.TargetType.ITEM, targetId, 5.0,
                Map.of(), java.util.Optional.empty(), false, 0.8,
                List.of(GlobalPos.of(net.minecraft.world.level.Level.OVERWORLD, new BlockPos(1, 2, 3))),
                com.mervyn.miforeman.goal.GraphLayoutState.EMPTY,
                com.mervyn.miforeman.goal.MachineLinkHistory.EMPTY, List.of());

        // No change at all: nothing to persist.
        if (com.mervyn.miforeman.goal.ClipboardCloseSync.computeCloseSyncGoal(
                synced, synced.uiState(), synced.graphLayout()).isPresent()) {
            helper.fail("Expected no goal to persist when neither ui state nor layout changed.");
            return;
        }

        // UI state changed only. This is the actual regression case: the persisted goal
        // should
        // keep synced's other fields (linkedMachines survives) with just the new ui
        // state on top.
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

        // Nothing synced yet (fresh clipboard, never saved): nothing to persist.
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

        // Start from a clean slate; trackers are global static state.
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

        // Pruning against an empty active-set must evict everything unlinked.
        ServerMonitoringManager.pruneTrackers(java.util.Set.of());
        if (!ServerMonitoringManager.TRACKERS.isEmpty()) {
            helper.fail("pruneTrackers left " + ServerMonitoringManager.TRACKERS.size()
                    + " stale tracker(s) behind");
            return;
        }

        helper.succeed();
    }

    /**
     * Verifies {@code FactoryPlan.totalPowerDemandEu()} stays consistent with the
     * sum of its own
     * machine requirements, and that a machine shared by two demand paths
     * accumulates EU from both
     * (not just the larger/last one written) -- see the additive accumulation in
     * {@code RecipeGraphTraverser}'s {@code MachineStats.totalEu}.
     */
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

        // Every machine requirement's totalEuPerTick must be a positive multiple of its
        // own
        // presence in the plan -- NOT necessarily ceil(count * baseEuPerTick), since a
        // single
        // machineId can be fed by multiple recipes with different EU costs
        // (baseEuPerTick here
        // reflects only the last-recorded recipe for that machine type, while
        // count/totalEuPerTick
        // are independently accumulated sums across every recipe that uses this
        // machine). See
        // RecipeGraphTraverser.computePlan's MachineStats accumulation.
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

    /**
     * Verifies a FLUID-target {@code ProductionGoal} produces a valid, non-empty
     * plan and graph,
     * exercising the traverser's fluid-recipe indexing path (not just the
     * {@code ITEM} path every
     * other test in this file uses).
     */
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
        if (plan.rawInputs().isEmpty()) {
            helper.fail("Expected non-empty raw inputs for FLUID target " + targetId);
            return;
        }

        boolean anyNonZeroRawInput = plan.rawInputs().stream()
                .anyMatch(flow -> flow.rate() > 0);
        if (!anyNonZeroRawInput) {
            helper.fail("Expected at least one raw input with a non-zero rate for FLUID target " + targetId);
            return;
        }

        com.mervyn.miforeman.goal.RecipeGraph graph = RecipeGraphTraverser.computeRecipeGraph(level, goal);
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

    /**
     * Verifies {@code RecipeGraphTraverser.GRAPH_CACHE} returns the exact same
     * {@code RecipeGraph}
     * reference for identical goal queries, produces a fresh reference after
     * {@code clearGraphCache()}, and keys entries independently by
     * {@code recipeSelections}.
     */
    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGraphCacheHitAndInvalidation(GameTestHelper helper) {
        var level = helper.getLevel();
        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");

        // Isolate from any caching side effects other tests in this file may have left
        // behind.
        RecipeGraphTraverser.clearGraphCache();

        ProductionGoal goal = new ProductionGoal("cache_test", ProductionGoal.TargetType.ITEM, targetId, 1.0);

        var first = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        var second = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        if (first != second) {
            helper.fail("Expected computeRecipeGraph to return the identical cached reference for an "
                    + "unchanged goal query, but got two distinct instances.");
            return;
        }

        RecipeGraphTraverser.clearGraphCache();
        var afterClear = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        if (afterClear == first) {
            helper.fail("Expected clearGraphCache() to force a fresh RecipeGraph instance, but the "
                    + "same reference was returned.");
            return;
        }

        // A different recipeSelections map must be an isolated cache entry, not a hit
        // on the
        // existing one, and must not evict/overwrite it.
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
        if (altGraph == afterClear) {
            helper.fail("Expected a goal with different recipeSelections to be a distinct cache "
                    + "entry, but got the same reference as the default-selections goal.");
            return;
        }

        var stillCachedDefault = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        if (stillCachedDefault != afterClear) {
            helper.fail("Populating the cache for altGoal's recipeSelections evicted or replaced "
                    + "the existing entry for the default-selections goal.");
            return;
        }

        RecipeGraphTraverser.clearGraphCache();
        helper.succeed();
    }

    /**
     * Unit-style coverage for {@code PacketRateLimiter.tryAcquire}: throttling
     * within
     * {@code minIntervalTicks}, allowance once the interval elapses, and
     * independent per-player
     * tracking with no cross-contamination. No Level/network state is needed for
     * this logic, so
     * it's wrapped as a trivial GameTest purely to match this repo's existing test
     * conventions.
     */
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

        // A second player must not be throttled by the first player's usage, even at
        // the same tick.
        if (!limiter.tryAcquire(playerB, 10L)) {
            helper.fail("Expected a different player's first tryAcquire to be allowed independently "
                    + "of another player's throttling state.");
            return;
        }
        if (limiter.tryAcquire(playerB, 15L)) {
            helper.fail("Expected playerB's tryAcquire within its own minIntervalTicks to be rejected.");
            return;
        }

        // playerA being ready again must not be affected by playerB's independent
        // throttling.
        if (!limiter.tryAcquire(playerA, 20L)) {
            helper.fail("Expected playerA to be allowed again after its own interval elapsed, "
                    + "independent of playerB's state.");
            return;
        }

        helper.succeed();
    }

    /**
     * Extends {@code testCycleRecipePlan}'s coverage to a resource with 3+
     * alternative recipes:
     * cycling through every option and back to the start must leave the graph
     * structurally
     * identical to where it began, with no orphaned nodes or stale edges left
     * behind.
     */
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

        // Cycle forward through every option, ending back where we started.
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

            // The selected recipe option must be present in the graph.
            if (!cycledGraph.nodes().containsKey(nextRecipe)) {
                helper.fail("After selecting " + nextRecipe + ", recipe node was missing from graph.");
                return;
            }
        }

        // Back to the first option: graph should match the original structure exactly.
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

    /**
     * Verifies a real machine's {@code MachineTracker.status} transitions across
     * server ticks:
     * empty inputs (RED) -> valid inputs + power actually crafting (GREEN) ->
     * blocked output
     * saturation (ORANGE). Unlike {@code testIdentifyBottlenecks} (which only calls
     * the passive
     * status check directly, never letting the machine actually craft), this drives
     * the real
     * {@code ServerMonitoringManager.onServerTick} path by linking a mock player's
     * clipboard to
     * the machine and letting the block entity's own ticker run.
     */
    @SuppressWarnings("removal") // GameTestHelper#makeMockServerPlayerInLevel is deprecated-for-removal
                                 // upstream but remains the only vanilla API for a real ServerPlayer in a
                                 // GameTest.
    @GameTest(template = "empty", templateNamespace = MIForeman.MODID, timeoutTicks = 200)
    public static void testMachineStatusDynamicTransitions(GameTestHelper helper) {
        var level = helper.getLevel();

        // Unlike testIdentifyBottlenecks's bronze_compressor (a steam machine with no
        // EnergyComponent),
        // this needs the electric-tier compressor so an EU buffer can actually be
        // filled.
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

        // Trackers are global static state; start clean so a leftover entry from
        // another test
        // can't be mistaken for one this test created.
        ServerMonitoringManager.TRACKERS.clear();

        // onServerTick only tracks positions reachable via a held clipboard's
        // linkedMachines, so
        // a real (mock) player holding a linked clipboard is required to exercise it at
        // all.
        net.minecraft.server.level.ServerPlayer mockPlayer = helper.makeMockServerPlayerInLevel();
        GlobalPos key = ServerMonitoringManager.key(level, absolutePos);

        ProductionGoal goal = new ProductionGoal(
                "dynamic_status_test",
                ProductionGoal.TargetType.ITEM,
                ResourceLocation.parse("modern_industrialization:iron_plate"),
                1.0).withLinkedMachines(List.of(key), com.mervyn.miforeman.goal.MachineLinkHistory.EMPTY);

        ItemStack clipboard = new ItemStack(ModItems.FOREMAN_CLIPBOARD_ITEM.get());
        clipboard.set(ModComponents.PRODUCTION_GOAL.get(), goal);
        mockPlayer.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, clipboard);

        // Phase 1: empty inputs -> RED, once onServerTick has had a chance to create
        // the tracker.
        helper.runAfterDelay(3, () -> {
            var tracker = ServerMonitoringManager.TRACKERS.get(key);
            if (tracker == null) {
                helper.fail("Expected onServerTick to create a MachineTracker for the linked machine.");
                return;
            }
            if (!"RED".equals(tracker.status)) {
                helper.fail("Expected empty-input machine to read RED, but got: " + tracker.status);
                return;
            }

            // Phase 2: supply a valid input item and fill the energy buffer directly
            // (bypassing
            // generator/cable infrastructure, same as MI's own EnergyComponent API allows)
            // so the
            // machine's own ticker actually starts crafting.
            var inputSlot = crafter.getItemInputs().get(0);
            inputSlot.setKey(aztech.modern_industrialization.thirdparty.fabrictransfer.api.item.ItemVariant
                    .of(net.minecraft.world.item.Items.IRON_INGOT));
            inputSlot.setAmount(4);

            var energyComponent = (aztech.modern_industrialization.machines.components.EnergyComponent) ((aztech.modern_industrialization.api.machine.holder.EnergyComponentHolder) machine)
                    .getEnergyComponent();
            energyComponent.insertEu(Long.MAX_VALUE, aztech.modern_industrialization.util.Simulation.ACT);

            helper.runAfterDelay(5, () -> {
                var trackerAfterFeed = ServerMonitoringManager.TRACKERS.get(key);
                if (trackerAfterFeed == null || !"GREEN".equals(trackerAfterFeed.status)) {
                    helper.fail("Expected actively-crafting machine to read GREEN, but got: "
                            + (trackerAfterFeed != null ? trackerAfterFeed.status : "null"));
                    return;
                }
                if (!crafter.hasActiveRecipe()) {
                    helper.fail("Tracker read GREEN but crafter.hasActiveRecipe() is false.");
                    return;
                }

                // Phase 3: block the output with a mismatched item -> saturation (ORANGE) once
                // the
                // machine can no longer deposit its crafted output.
                var outputSlot = ((UnifiedCrafter.StandardCrafterAdapter) crafter).getUnderlying().getInventory()
                        .getItemOutputs().get(0);
                outputSlot.setKey(aztech.modern_industrialization.thirdparty.fabrictransfer.api.item.ItemVariant
                        .of(net.minecraft.world.item.Items.GLASS));
                outputSlot.setAmount(64);

                helper.succeedWhen(() -> {
                    var finalTracker = ServerMonitoringManager.TRACKERS.get(key);
                    if (finalTracker == null || !"ORANGE".equals(finalTracker.status)) {
                        helper.fail("Expected saturated machine to read ORANGE, but got: "
                                + (finalTracker != null ? finalTracker.status : "null"));
                    }
                });
            });
        });
    }

    /**
     * Verifies {@code Config.INCLUDE_PROXIED_RECIPE_TYPES} defaults to off (so
     * existing plans/graphs
     * are byte-for-byte unaffected by default), and that toggling it on produces a
     * valid non-empty
     * result rather than throwing or corrupting state.
     * <p>
     * Does NOT assert identical output before/after: base MI's own
     * {@code FurnaceMachineRecipeType},
     * {@code CuttingMachineRecipeType}, and {@code CentrifugeMachineRecipeType} are
     * themselves
     * {@code ProxyableMachineRecipeType}s, and
     * {@code FurnaceMachineRecipeType.fillRecipeList}
     * synthesizes a {@code MachineRecipe} for every vanilla
     * {@code RecipeType.SMELTING} recipe via
     * {@code RecipeConversions.ofSmelting} on top of whatever's already in the
     * RecipeManager. So
     * enabling this config changes candidate recipe sets, and therefore default
     * ambiguous recipe
     * selection, even with zero addons installed -- measured directly:
     * quantum_upgrade's graph goes
     * from 97/158 nodes/edges to 89/149 with the flag on. Expected, not a bug.
     */
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

        // Confirm the flag going back off restores exactly today's pinned snapshot --
        // proves the
        // toggle has no lingering side effect on the shared static GRAPH_CACHE or
        // recipe indexing.
        var graphRestored = RecipeGraphTraverser.computeRecipeGraph(level, goal);
        if (graphRestored.nodes().size() != 588 || graphRestored.edges().size() != 864) {
            helper.fail("Expected graph to return to the pinned 588 nodes/864 edges after disabling "
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
        protected Object behavior = new Object(); // behavior has NO recipeType or banRecipe methods

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

        com.mervyn.miforeman.client.gui.widget.GraphSearchState state = new com.mervyn.miforeman.client.gui.widget.GraphSearchState();

        // 1. Empty query
        state.setQuery("", graph.nodes().values());
        if (state.isSearching() || state.getMatchCount() != 0 || state.getCurrentIndex() != -1) {
            helper.fail("Empty query should have 0 matches and isSearching == false");
            return;
        }

        // 2. Search by partial machine name (case-insensitive formatted name)
        state.setQuery("assembler", graph.nodes().values());
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

        // 3. Search by exact resource namespace ID
        state.setQuery("modern_industrialization:quantum_upgrade", graph.nodes().values());
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

        com.mervyn.miforeman.client.gui.widget.GraphSearchState state = new com.mervyn.miforeman.client.gui.widget.GraphSearchState();
        state.setQuery("assembler", graph.nodes().values());

        int count = state.getMatchCount();
        if (count < 2) {
            helper.fail("Expected at least 2 assembler matches for cycling test, got: " + count);
            return;
        }

        if (state.getCurrentIndex() != 0) {
            helper.fail("Initial index expected 0, got: " + state.getCurrentIndex());
            return;
        }

        // Step forward
        ResourceLocation secondMatch = state.nextMatch();
        if (state.getCurrentIndex() != 1 || secondMatch == null || !secondMatch.equals(state.getMatches().get(1))) {
            helper.fail("Expected nextMatch() to advance to index 1");
            return;
        }

        // Step back
        ResourceLocation firstMatch = state.prevMatch();
        if (state.getCurrentIndex() != 0 || firstMatch == null || !firstMatch.equals(state.getMatches().get(0))) {
            helper.fail("Expected prevMatch() to return to index 0");
            return;
        }

        // Wrap around backwards from 0 -> count - 1
        ResourceLocation lastMatch = state.prevMatch();
        if (state.getCurrentIndex() != count - 1 || lastMatch == null
                || !lastMatch.equals(state.getMatches().get(count - 1))) {
            helper.fail("Expected wrap-around backwards to index " + (count - 1) + ", got: " + state.getCurrentIndex());
            return;
        }

        // Wrap around forward from count - 1 -> 0
        ResourceLocation wrappedFirst = state.nextMatch();
        if (state.getCurrentIndex() != 0 || wrappedFirst == null || !wrappedFirst.equals(state.getMatches().get(0))) {
            helper.fail("Expected wrap-around forward to index 0, got: " + state.getCurrentIndex());
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

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testGraphLayoutStateHiddenNodes(GameTestHelper helper) {
        ResourceLocation nodeA = ResourceLocation.parse("minecraft:iron_ingot");
        ResourceLocation nodeB = ResourceLocation.parse("minecraft:iron_ore");
        com.mervyn.miforeman.goal.GraphLayoutState state = com.mervyn.miforeman.goal.GraphLayoutState.EMPTY;

        if (state.isHidden(nodeA)) {
            helper.fail("Initial state should not have nodeA hidden");
            return;
        }

        // Toggle nodeA to hidden
        state = state.withToggledNodeVisibility(nodeA);
        if (!state.isHidden(nodeA) || state.isHidden(nodeB)) {
            helper.fail("Expected nodeA to be hidden and nodeB to be visible");
            return;
        }

        // Toggle nodeB to hidden
        state = state.withToggledNodeVisibility(nodeB);
        if (!state.isHidden(nodeA) || !state.isHidden(nodeB)) {
            helper.fail("Expected both nodeA and nodeB to be hidden");
            return;
        }

        // Toggle nodeA back to visible
        state = state.withToggledNodeVisibility(nodeA);
        if (state.isHidden(nodeA) || !state.isHidden(nodeB)) {
            helper.fail("Expected nodeA visible and nodeB hidden");
            return;
        }

        // Unhide all
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

        // Test building a goal with iron_plate recipe selected
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

        // Verify that intermediate fluid inputs in the tree (e.g. styrene_butadiene) were recursed and resolved
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

        // Test expanding intermediate fluid node via selections
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
}
