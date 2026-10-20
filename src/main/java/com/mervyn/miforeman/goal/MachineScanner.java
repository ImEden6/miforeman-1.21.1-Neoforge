package com.mervyn.miforeman.goal;

import aztech.modern_industrialization.machines.MachineBlockEntity;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MachineScanner {
    public record ScanCandidate(GlobalPos pos, ResourceLocation machineId, @Nullable ResourceLocation recipeId) {}

    public static Set<ResourceLocation> buildRecipeIndex(RecipeGraph graph) {
        Set<ResourceLocation> index = new HashSet<>();
        for (RecipeGraphNode node : graph.nodes().values()) {
            if (node.getType() == NodeType.MACHINE) {
                index.add(node.getId());
            }
        }
        return index;
    }

    /** Means the server's configured default. The client can't resolve it, since the config is
     *  COMMON and a dedicated server's value never reaches it. */
    public static final int DEFAULT_RADIUS = 0;

    /** Matches the top of both radius config ranges. */
    public static final int MAX_SCAN_RADIUS = 16;

    /** DEFAULT_RADIUS resolves to the configured default. Both are capped at maxRadius, so an
     *  admin who lowers the cap below the default isn't overridden. */
    public static int effectiveScanRadius(int requested, int defaultRadius, int maxRadius) {
        int wanted = requested == DEFAULT_RADIUS ? defaultRadius : requested;
        return Math.max(1, Math.min(wanted, maxRadius));
    }

    /** Must cover the widest scan a player can run, or a machine found by it is silently dropped
     *  on link. Never below the default either. */
    public static int linkValidationRadius(int defaultRadius, int maxRadius) {
        return Math.max(defaultRadius, maxRadius);
    }

    /** From DEFAULT_RADIUS this steps from the last resolved default, else from localDefault,
     *  which is only a guess on a dedicated server until the first scan reports. */
    public static int stepScanRadius(int currentPick, int lastRunRadius, int localDefault, int delta) {
        int from = currentPick != DEFAULT_RADIUS ? currentPick
                : lastRunRadius > 0 ? lastRunRadius
                : localDefault;
        return Math.max(1, Math.min(from + delta, MAX_SCAN_RADIUS));
    }

    /** If the server capped the scan, show the capped value. A default pick stays default. */
    public static int pickAfterScan(int currentPick, int ranAtRadius) {
        if (currentPick != DEFAULT_RADIUS && ranAtRadius < currentPick) {
            return ranAtRadius;
        }
        return currentPick;
    }

    /** Positions in found that aren't in known: linked, rejected, or seen by the previous scan. */
    public static List<GlobalPos> newlyFound(List<GlobalPos> found, Set<GlobalPos> known) {
        List<GlobalPos> result = new ArrayList<>();
        for (GlobalPos pos : found) {
            if (!known.contains(pos)) {
                result.add(pos);
            }
        }
        return result;
    }

    public static boolean isWithinScanRadius(BlockPos center, BlockPos pos, int radiusChunks) {
        ChunkPos centerChunk = new ChunkPos(center);
        ChunkPos posChunk = new ChunkPos(pos);
        return Math.abs(posChunk.x - centerChunk.x) <= radiusChunks
                && Math.abs(posChunk.z - centerChunk.z) <= radiusChunks;
    }

    public static List<ScanCandidate> scan(ServerLevel level, BlockPos center, int radiusChunks, Set<ResourceLocation> recipeIndex) {
        List<ScanCandidate> found = new ArrayList<>();
        ChunkPos centerChunk = new ChunkPos(center);
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = centerChunk.x + dx;
                int cz = centerChunk.z + dz;
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof MachineBlockEntity machine)) continue;
                    UnifiedCrafter crafter = ServerMonitoringManager.getCrafter(machine);
                    if (crafter == null) continue;
                    RecipeHolder<MachineRecipe> active = crafter.getActiveRecipe();
                    ResourceLocation recipeId = active != null ? active.id() : null;
                    // A just-loaded machine can have a null activeRecipe while delayedActiveRecipe is pending.
                    // Skip it to avoid reflecting on internal fields.
                    if (recipeId == null || !recipeIndex.contains(recipeId)) continue;
                    ResourceLocation machineId = BuiltInRegistries.BLOCK.getKey(machine.getBlockState().getBlock());
                    found.add(new ScanCandidate(GlobalPos.of(level.dimension(), be.getBlockPos()), machineId, recipeId));
                }
            }
        }
        return found;
    }

    private MachineScanner() {
    }
}
