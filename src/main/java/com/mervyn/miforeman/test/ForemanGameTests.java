package com.mervyn.miforeman.test;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.goal.ProductionGoal;
import com.mervyn.miforeman.goal.RecipeGraphTraverser;
import com.mervyn.miforeman.registry.ModComponents;
import com.mervyn.miforeman.registry.ModItems;
import aztech.modern_industrialization.machines.init.MIMachineRecipeTypes;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Collection;
import aztech.modern_industrialization.machines.MachineBlockEntity;
import aztech.modern_industrialization.machines.components.CrafterComponent;
import com.mervyn.miforeman.goal.ServerMonitoringManager;
import net.minecraft.core.BlockPos;
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
    public static void testReadMIRecipes(GameTestHelper helper) {
        var level = helper.getLevel();
        var recipeManager = level.getRecipeManager();
        int totalRecipes = 0;

        MIForeman.LOGGER.info("Starting MI Recipe Registry Extraction feasibility spike in GameTest...");

        for (var recipeType : MIMachineRecipeTypes.getRecipeTypes()) {
            Collection<RecipeHolder<MachineRecipe>> recipes = recipeManager.getAllRecipesFor(recipeType);
            MIForeman.LOGGER.info("Recipe Type: {} - Found {} recipes", recipeType.getId(), recipes.size());
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

        ResourceLocation pvcId = ResourceLocation.parse("modern_industrialization:polyvinyl_chloride");
        double pvcRate = plan.rawInputs().stream()
                .filter(flow -> flow.resourceId().equals(pvcId))
                .mapToDouble(ProductionGoal.MaterialFlow::rate)
                .sum();

        if (Math.abs(pvcRate - 461500.0) > 0.001) {
            helper.fail("Expected 461500.0 polyvinyl_chloride rate, but calculated: " + pvcRate);
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
}

