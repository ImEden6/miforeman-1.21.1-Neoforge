package com.mervyn.miforeman.client;

import com.mervyn.miforeman.goal.FailureReason;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;

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
}
