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
 * The last recipe each linked machine was seen running, saved with the world.
 *
 * <p>MI clears a machine's active recipe as soon as it idles, and {@link ServerMonitoringManager}'s
 * trackers live in memory: they're pruned whenever no held clipboard links a machine (putting
 * the clipboard away for a few seconds) and lost on restart. Without this, an idle machine
 * dropped off the recipe graph until it next crafted. This is a fact about the machine, not
 * about any one goal, so it lives with the world rather than on a clipboard: two clipboards
 * linking the same machine agree, and nothing is ever written to a held item.
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
        // Overworld storage regardless of the machine's dimension: keys are GlobalPos already.
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public @Nullable ResourceLocation get(GlobalPos pos) {
        return lastRecipes.get(pos);
    }

    /** Records {@code recipeId} as {@code pos}'s latest recipe. Only marks the data dirty when it
     *  actually changes, so a machine running the same recipe every tick costs nothing. */
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
            // A bad entry (e.g. a dimension id that no longer parses) drops the list rather than
            // the world load; worst case, idle machines wait for their next craft again.
            ENTRIES_CODEC.parse(NbtOps.INSTANCE, encoded)
                    .ifSuccess(entries -> entries.forEach(e -> history.lastRecipes.put(e.pos(), e.recipeId())));
        }
        return history;
    }
}
