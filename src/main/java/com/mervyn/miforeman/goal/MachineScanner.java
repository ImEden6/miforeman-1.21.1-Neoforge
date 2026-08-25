package com.mervyn.miforeman.goal;

import aztech.modern_industrialization.machines.MachineBlockEntity;
import aztech.modern_industrialization.machines.components.CrafterComponent;
import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import net.minecraft.core.BlockPos;
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

/**
 * Scans a chunk radius for machines that match recipe IDs in a factory plan.
 * Query utility that reuses {@link ServerMonitoringManager} helpers.
 */
public class MachineScanner {
    public record ScanCandidate(BlockPos pos, ResourceLocation machineId, @Nullable ResourceLocation recipeId) {}

    /**
     * Extracts recipe IDs from all machine nodes in the graph.
     */
    public static Set<ResourceLocation> buildRecipeIndex(RecipeGraph graph) {
        Set<ResourceLocation> index = new HashSet<>();
        for (RecipeGraphNode node : graph.nodes().values()) {
            if (node.getType() == NodeType.MACHINE) {
                index.add(node.getId());
            }
        }
        return index;
    }

    /** Returns true if {@code pos} lies within the square chunk grid bounded by {@code radiusChunks}. */
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
                if (!level.hasChunk(cx, cz)) continue; // only inspect already-loaded chunks, don't force-load
                LevelChunk chunk = level.getChunk(cx, cz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof MachineBlockEntity machine)) continue;
                    CrafterComponent crafter = ServerMonitoringManager.getCrafter(machine);
                    if (crafter == null) continue;
                    RecipeHolder<MachineRecipe> active = ServerMonitoringManager.getActiveRecipeHolder(crafter);
                    ResourceLocation recipeId = active != null ? active.id() : null;
                    // Edge case (accepted, not fixed): a just-loaded machine may have activeRecipe==null
                    // but a pending delayedActiveRecipe about to populate next tick. This scan skips it
                    // rather than reflecting on a second CrafterComponent field -- small false-negative
                    // window, player can re-scan. Revisit only if this proves annoying in practice.
                    if (recipeId == null || !recipeIndex.contains(recipeId)) continue;
                    ResourceLocation machineId = BuiltInRegistries.BLOCK.getKey(machine.getBlockState().getBlock());
                    found.add(new ScanCandidate(be.getBlockPos(), machineId, recipeId));
                }
            }
        }
        return found;
    }

    private MachineScanner() {
    }
}
