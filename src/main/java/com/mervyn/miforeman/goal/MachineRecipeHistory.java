package com.mervyn.miforeman.goal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The last recipe each linked machine ran, saved with the world.
 *
 * <p>MI clears a machine's active recipe when it idles, and monitoring trackers live in memory,
 * pruned when no held clipboard links the machine and lost on restart.
 */
public class MachineRecipeHistory extends SavedData {
    private static final String NAME = "miforeman_machine_recipes";

    private record Entry(GlobalPos pos, ResourceLocation recipeId) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                GlobalPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                ResourceLocation.CODEC.fieldOf("recipe").forGetter(Entry::recipeId)
        ).apply(instance, Entry::new));
    }

    private static final Codec<List<Entry>> ENTRIES_CODEC = Entry.CODEC.listOf();

    public static final SavedData.Factory<MachineRecipeHistory> FACTORY =
            new SavedData.Factory<>(MachineRecipeHistory::new, MachineRecipeHistory::load, null);

    private final Map<GlobalPos, ResourceLocation> lastRecipes = new HashMap<>();

    public static MachineRecipeHistory get(MinecraftServer server) {
        // Always overworld storage. GlobalPos keys already carry the dimension.
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public @Nullable ResourceLocation get(GlobalPos pos) {
        return lastRecipes.get(pos);
    }

    /** Marks the data dirty only on change, so a machine repeating a recipe costs nothing. */
    public void record(GlobalPos pos, ResourceLocation recipeId) {
        if (!recipeId.equals(lastRecipes.put(pos, recipeId))) {
            setDirty();
        }
    }

    public void forget(GlobalPos pos) {
        if (lastRecipes.remove(pos) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        List<Entry> entries = lastRecipes.entrySet().stream()
                .map(e -> new Entry(e.getKey(), e.getValue()))
                .toList();
        ENTRIES_CODEC.encodeStart(NbtOps.INSTANCE, entries)
                .ifSuccess(encoded -> tag.put("entries", encoded));
        return tag;
    }

    public static MachineRecipeHistory load(CompoundTag tag, HolderLookup.Provider registries) {
        MachineRecipeHistory history = new MachineRecipeHistory();
        Tag encoded = tag.get("entries");
        if (encoded != null) {
            // A bad entry drops the list, not the whole world load.
            ENTRIES_CODEC.parse(NbtOps.INSTANCE, encoded)
                    .ifSuccess(entries -> entries.forEach(e -> history.lastRecipes.put(e.pos(), e.recipeId())));
        }
        return history;
    }
}
