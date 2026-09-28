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

/**
 * Scans a chunk radius for machines that match recipe IDs in a factory plan.
 * Query utility that reuses {@link ServerMonitoringManager} helpers.
 */
public class MachineScanner {
    public record ScanCandidate(GlobalPos pos, ResourceLocation machineId, @Nullable ResourceLocation recipeId) {}

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

    /** Sentinel radius in a scan request meaning "use the server's configured default". The
     *  client can't pick the default itself: the config is COMMON, so a dedicated server's
     *  value never reaches it. */
    public static final int DEFAULT_RADIUS = 0;

    /** Hard ceiling on any scan radius, matching the top of both radius config ranges. */
    public static final int MAX_SCAN_RADIUS = 16;

    /** The radius a scan actually runs at. {@link #DEFAULT_RADIUS} resolves to the configured
     *  default; anything else is the player's pick. Both are capped at {@code maxRadius}, so an
     *  admin who lowers the cap below the default isn't overridden by it. */
    public static int effectiveScanRadius(int requested, int defaultRadius, int maxRadius) {
        int wanted = requested == DEFAULT_RADIUS ? defaultRadius : requested;
        return Math.max(1, Math.min(wanted, maxRadius));
    }

    /** How far from the player a newly linked machine may be. Must cover the widest scan a
     *  player can run, or accepting a machine found by a wide scan silently drops it. Never
     *  tighter than the default either, so lowering the cap doesn't break default-radius links. */
    public static int linkValidationRadius(int defaultRadius, int maxRadius) {
        return Math.max(defaultRadius, maxRadius);
    }

    /** The radius the scan stepper moves to. From {@link #DEFAULT_RADIUS} it steps from what the
     *  default last resolved to ({@code lastRunRadius}, 0 before any scan), else from
     *  {@code localDefault}: the client's own config, exact in singleplayer and a best guess on a
     *  dedicated server until the first scan reports the real value. */
    public static int stepScanRadius(int currentPick, int lastRunRadius, int localDefault, int delta) {
        int from = currentPick != DEFAULT_RADIUS ? currentPick
                : lastRunRadius > 0 ? lastRunRadius
                : localDefault;
        return Math.max(1, Math.min(from + delta, MAX_SCAN_RADIUS));
    }

    /** The pick to show once a scan reports the radius it actually ran at: if the server capped
     *  it, show the capped value rather than the one asked for. A default pick stays default. */
    public static int pickAfterScan(int currentPick, int ranAtRadius) {
        if (currentPick != DEFAULT_RADIUS && ranAtRadius < currentPick) {
            return ranAtRadius;
        }
        return currentPick;
    }

    /** Positions from {@code found} worth pointing out in the world: anything not already in
     *  {@code known} (linked, rejected, or turned up by the previous scan). */
    public static List<GlobalPos> newlyFound(List<GlobalPos> found, Set<GlobalPos> known) {
        List<GlobalPos> result = new ArrayList<>();
        for (GlobalPos pos : found) {
            if (!known.contains(pos)) {
                result.add(pos);
            }
        }
        return result;
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
                    UnifiedCrafter crafter = ServerMonitoringManager.getCrafter(machine);
                    if (crafter == null) continue;
                    RecipeHolder<MachineRecipe> active = crafter.getActiveRecipe();
                    ResourceLocation recipeId = active != null ? active.id() : null;
                    // A recently loaded machine may have activeRecipe == null while delayedActiveRecipe
                    // is still pending. The scan skips it to avoid reflecting on internal fields.
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
