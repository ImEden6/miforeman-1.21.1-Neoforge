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
import aztech.modern_industrialization.machines.components.CrafterComponent;
import com.mervyn.miforeman.goal.ServerMonitoringManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
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
                        List.of(new ProductionGoal.MachineRequirement(ResourceLocation.parse("modern_industrialization:assembler"), 3.0)),
                        List.of(new ProductionGoal.MaterialFlow(ProductionGoal.TargetType.ITEM, ResourceLocation.parse("minecraft:iron_ingot"), 4.0)),
                        List.of(),
                        List.of(new ProductionGoal.Ambiguity(ResourceLocation.parse("minecraft:dye"), List.of(ResourceLocation.parse("minecraft:red_dye"))))
                )),
                true,
                0.6,
                List.of(new BlockPos(1, 2, 3)),
                com.mervyn.miforeman.goal.GraphLayoutState.EMPTY,
                com.mervyn.miforeman.goal.MachineLinkHistory.EMPTY,
                List.of(new BlockPos(4, 5, 6)),
                new com.mervyn.miforeman.goal.ClipboardUiState(1, 12.5, -3.0, 2.0f, false, false, true, true)
        );

        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
        ProductionGoal.STREAM_CODEC.encode(buf, goal);
        ProductionGoal decoded = ProductionGoal.STREAM_CODEC.decode(buf);

        if (!decoded.equals(goal)) {
            helper.fail("STREAM_CODEC round-trip does not match original ProductionGoal -- a field was likely "
                    + "added to CODEC without updating STREAM_CODEC (or vice versa). Original: " + goal + ", decoded: " + decoded);
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

        Map<ResourceLocation, List<RecipeHolder<MachineRecipe>>> byType = RecipeGraphTraverser.groupMachineRecipesByType(recipeManager);

        for (var entry : byType.entrySet()) {
            var recipes = entry.getValue();
            MIForeman.LOGGER.info("Recipe Type: {} - Found {} recipes", entry.getKey(), recipes.size());
            totalRecipes += recipes.size();
            for (var recipeHolder : recipes) {
                var recipe = recipeHolder.value();
                MIForeman.LOGGER.info("  Recipe: {} ({} ticks, {} EU/t)", recipeHolder.id(), recipe.duration, recipe.eu);
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
            MIForeman.LOGGER.info("  - {}: {}", req.machineId(), req.count());
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
            helper.fail("Expected at least 210.0 assemblers for quantum_upgrade plan, but calculated: " + assemblerCount);
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

        // polyvinyl_chloride has no recipe of its own in this recipe set; it's a leaf raw input
        // (confirmed via computeRecipeGraph, whose node for it has empty ambiguityOptions). So this
        // 461500.0 -> 437500.0 shift isn't PVC's own default-recipe flip. indexMachineRecipes now
        // sorts each resourceId's candidate list by recipe id (see its doc comment), instead of
        // leaving it in RecipeManager's undefined order. One of the ~110 ambiguous intermediates
        // upstream of quantum_upgrade picked a different default candidate under the new sort, and
        // that candidate consumes PVC, directly or transitively, at a different rate. To find which
        // one, diff plan.ambiguities() against a pre-sort build if this value ever needs re-pinning.
        ResourceLocation pvcId = ResourceLocation.parse("modern_industrialization:polyvinyl_chloride");
        double pvcRate = plan.rawInputs().stream()
                .filter(flow -> flow.resourceId().equals(pvcId))
                .mapToDouble(ProductionGoal.MaterialFlow::rate)
                .sum();

        if (Math.abs(pvcRate - 437500.0) > 0.001) {
            helper.fail("Expected 437500.0 polyvinyl_chloride rate, but calculated: " + pvcRate);
            return;
        }

        MIForeman.LOGGER.info("UC2 complex recipe graph traversal test passed successfully!");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testIdentifyBottlenecks(GameTestHelper helper) {
        var level = helper.getLevel();

        var compressorBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("modern_industrialization:bronze_compressor")
        );
        BlockPos relativePos = new BlockPos(1, 2, 1);
        BlockPos absolutePos = helper.absolutePos(relativePos);

        helper.setBlock(relativePos, compressorBlock);

        BlockEntity be = level.getBlockEntity(absolutePos);
        if (!(be instanceof MachineBlockEntity machine)) {
            helper.fail("Placed block is not a MachineBlockEntity!");
            return;
        }

        CrafterComponent crafter = ServerMonitoringManager.getCrafter(machine);
        if (crafter == null) {
            helper.fail("Placed Compressor does not have a CrafterComponent!");
            return;
        }

        // Test 1: Empty inputs -> Starving (RED)
        String statusStarving = ServerMonitoringManager.getMachinePassiveStatus(crafter, level);
        if (!"RED".equals(statusStarving)) {
            helper.fail("Expected machine with empty inputs to be STARVING (RED), but got: " + statusStarving);
            return;
        }

        // Test 2: Add valid inputs, but block outputs -> Saturating (ORANGE)
        var ironIngot = net.minecraft.world.item.Items.IRON_INGOT;
        var inputSlot = crafter.getInventory().getItemInputs().get(0);
        inputSlot.setKey(aztech.modern_industrialization.thirdparty.fabrictransfer.api.item.ItemVariant.of(ironIngot));
        inputSlot.setAmount(1);

        var outputSlot = crafter.getInventory().getItemOutputs().get(0);
        outputSlot.setKey(aztech.modern_industrialization.thirdparty.fabrictransfer.api.item.ItemVariant.of(net.minecraft.world.item.Items.GLASS));
        outputSlot.setAmount(64);

        String statusSaturating = ServerMonitoringManager.getMachinePassiveStatus(crafter, level);
        if (!"ORANGE".equals(statusSaturating)) {
            helper.fail("Expected machine with matched inputs but blocked outputs to be SATURATING (ORANGE), but got: " + statusSaturating);
            return;
        }

        // Test 3: Clear outputs -> ORANGE (ready but inactive)
        outputSlot.empty();
        String statusReady = ServerMonitoringManager.getMachinePassiveStatus(crafter, level);
        if (!"ORANGE".equals(statusReady)) {
            helper.fail("Expected ready-to-craft machine to return ORANGE (since it can start but is not currently active), but got: " + statusReady);
            return;
        }

        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = MIForeman.MODID)
    public static void testCycleRecipePlan(GameTestHelper helper) {
        var level = helper.getLevel();

        ResourceLocation targetId = ResourceLocation.parse("modern_industrialization:quantum_upgrade");
        double rate = 1.0;

        ProductionGoal goal = new ProductionGoal("cycle_test", ProductionGoal.TargetType.ITEM, targetId, rate);

        // Compute initial plan and graph
        var initialGraph = RecipeGraphTraverser.computeRecipeGraph(level, goal);

        // Find the first resource node with ambiguities
        com.mervyn.miforeman.goal.RecipeGraphNode ambiguousNode = null;
        for (var node : initialGraph.nodes().values()) {
            if (node.getType() != com.mervyn.miforeman.goal.NodeType.MACHINE && !node.getAmbiguityOptions().isEmpty()) {
                ambiguousNode = node;
                break;
            }
        }

        if (ambiguousNode == null) {
            helper.fail("Expected to find at least one ambiguous resource node in the quantum_upgrade plan.");
            return;
        }

        java.util.List<ResourceLocation> options = ambiguousNode.getAmbiguityOptions();
        if (options.size() < 2) {
            helper.fail("Ambiguous node " + ambiguousNode.getId() + " had fewer than 2 options: " + options.size());
            return;
        }

        ResourceLocation firstRecipe = options.get(0);
        ResourceLocation secondRecipe = options.get(1);

        // Update selections to second recipe choice
        java.util.Map<ResourceLocation, ResourceLocation> selections = new java.util.HashMap<>(goal.recipeSelections());
        selections.put(ambiguousNode.getId(), secondRecipe);

        ProductionGoal updatedGoal = new ProductionGoal(
            goal.name(), goal.type(), goal.targetId(), goal.rate(),
            selections, java.util.Optional.empty(), goal.perHour(),
            goal.threshold(), goal.linkedMachines()
        );

        // Recompute plan and graph
        var updatedGraph = RecipeGraphTraverser.computeRecipeGraph(level, updatedGoal);

        // Assert that the updated graph contains the second recipe node but NOT the first recipe node
        boolean hasFirstRecipe = updatedGraph.nodes().containsKey(firstRecipe);
        boolean hasSecondRecipe = updatedGraph.nodes().containsKey(secondRecipe);

        if (hasFirstRecipe) {
            helper.fail("After cycling recipe to " + secondRecipe + ", the graph still contained the default recipe " + firstRecipe);
            return;
        }

        if (!hasSecondRecipe) {
            helper.fail("After cycling recipe to " + secondRecipe + ", the graph did not contain the selected recipe node.");
            return;
        }

        // Verify that the resource node's selected ambiguity is updated
        var updatedResNode = updatedGraph.nodes().get(ambiguousNode.getId());
        if (updatedResNode == null) {
            helper.fail("Resource node " + ambiguousNode.getId() + " is missing from the updated graph.");
            return;
        }

        if (!secondRecipe.equals(updatedResNode.getSelectedAmbiguity())) {
            helper.fail("Expected selected ambiguity to be " + secondRecipe + ", but got: " + updatedResNode.getSelectedAmbiguity());
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
     * Checks that {@code RecipeGraphTraverser} never produces two edges for the same (from,to)
     * pair. {@code Map<EdgeKey,GraphEdge>} guarantees this structurally now, fixing the old
     * {@code List.contains}-based dedup, which compared full record equality including
     * {@code rate}, so the same pair could appear twice with different partial rates (see the
     * two-phase-rewrite comment on {@code computeRecipeGraph}). This test checks the real
     * quantum_upgrade graph for duplicate pairs, then pins its node and edge counts as a
     * regression snapshot. A reversion back to append-without-merge would inflate
     * {@code edges.size()} the moment any resourceId is demanded via more than one path.
     * <p>
     * It doesn't exercise the multi-output-recipe-reuse case directly: a MACHINE node chosen as
     * producer for two or more independently-demanded resourceIds, like a Distillation Tower's two
     * outputs (see {@code finalizeResourceNode}'s doc comment). Quantum_upgrade's real recipe tree
     * doesn't contain one. I confirmed this by grouping edges by a MACHINE-typed {@code from} and
     * checking for more than one distinct {@code to} per machine, and found none. So the
     * sum-not-overwrite behavior that case is meant to fix stays untested against real recipe data.
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

        if (graph.edges().size() != 150) {
            helper.fail("Expected 150 edges in the quantum_upgrade graph, but got: " + graph.edges().size()
                    + ". If this changed intentionally, e.g. an MI recipe update, update this snapshot.");
            return;
        }
        if (graph.nodes().size() != 89) {
            helper.fail("Expected 89 nodes in the quantum_upgrade graph, but got: " + graph.nodes().size()
                    + ". If this changed intentionally, e.g. an MI recipe update, update this snapshot.");
            return;
        }

        helper.succeed();
    }

    /**
     * Checks {@code RecipeGraphNode.ambiguityOwnerId} -- the resourceId a node's
     * {@code ambiguityOptions}/{@code selectedAmbiguity} actually describe, added so
     * {@code DetailCard}'s cycle-button click handler can target the right resourceId instead of
     * guessing one from {@code outputs.get(0)} (see {@code finalizeResourceNode}'s doc comment).
     * For a resource node (RAW/INTERMEDIATE/TARGET) the owner is always its own id. For a MACHINE
     * node it must be one of the resourceIds that node actually produces (one of its output edges'
     * {@code to()}), never null when the node has ambiguity options, and never a resourceId the
     * node doesn't produce at all.
     * <p>
     * Like {@code testRecipeGraphEdgesAreDeduped}, this can't exercise the multi-output-recipe
     * case (a MACHINE node reused across more than one demanded resourceId) against real data --
     * quantum_upgrade's tree doesn't contain one -- so it only pins the invariant that must hold
     * regardless of how many resourceIds a MACHINE node ends up shared across.
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
                // ambiguity data, so ambiguityOwnerId is null and there's nothing to check here.
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
                List.of(new BlockPos(1, 2, 3)), com.mervyn.miforeman.goal.GraphLayoutState.EMPTY,
                com.mervyn.miforeman.goal.MachineLinkHistory.EMPTY, List.of());

        // No change at all: nothing to persist.
        if (com.mervyn.miforeman.goal.ClipboardCloseSync.computeCloseSyncGoal(
                synced, synced.uiState(), synced.graphLayout()).isPresent()) {
            helper.fail("Expected no goal to persist when neither ui state nor layout changed.");
            return;
        }

        // UI state changed only. This is the actual regression case: the persisted goal should
        // keep synced's other fields (linkedMachines survives) with just the new ui state on top.
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
}

