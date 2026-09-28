package com.mervyn.miforeman.client;

import aztech.modern_industrialization.machines.recipe.MachineRecipe;
import com.mervyn.miforeman.goal.FailureReason;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side text and resource ID formatting utilities.
 */
public final class DisplayFormat {
    public static String formatId(ResourceLocation id) {
        String path = id.getPath();
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)))
                  .append(part.substring(1))
                  .append(" ");
            }
        }
        return sb.toString().trim();
    }

    /** Formats a rate as "12.3/m", or "12.3/h" (scaling by 60) when {@code perHour} is true. */
    public static String formatRate(double rate, boolean perHour) {
        double rateVal = rate * (perHour ? 60.0 : 1.0);
        return String.format("%.1f/%s", rateVal, perHour ? "h" : "m");
    }

    /** Short player-facing suffix for a {@link FailureReason}, e.g. " (dead-loop: wire in a
     *  source)". Empty when there's nothing more specific to say than the status color itself. */
    public static String formatFailureReason(FailureReason reason) {
        return switch (reason) {
            case DEAD_LOOP -> " (" + I18n.get("miforeman.status.reason.dead_loop") + ")";
            case CLOG_LOCK -> " (" + I18n.get("miforeman.status.reason.clog_lock") + ")";
            case DISPOSAL_THROTTLED -> " (" + I18n.get("miforeman.status.reason.disposal_throttled") + ")";
            case STARVED, NONE -> "";
        };
    }

    private DisplayFormat() {
    }

    /**
     * Resolves a recipe ID to formatted product names, or null if the recipe cannot
     * be found.
     */
    public static @Nullable String productLabel(ResourceLocation recipeId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return null;
        var holder = mc.level.getRecipeManager().byKey(recipeId).orElse(null);
        if (holder == null || !(holder.value() instanceof MachineRecipe recipe))
            return null;

        List<String> names = new ArrayList<>();
        for (var output : recipe.itemOutputs) {
            if (output.amount() > 0 && output.probability() > 0) {
                names.add(formatId(BuiltInRegistries.ITEM.getKey(output.variant().getItem())));
            }
        }
        for (var output : recipe.fluidOutputs) {
            if (output.amount() > 0 && output.probability() > 0) {
                names.add(formatId(BuiltInRegistries.FLUID.getKey(output.fluid())));
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }
}
