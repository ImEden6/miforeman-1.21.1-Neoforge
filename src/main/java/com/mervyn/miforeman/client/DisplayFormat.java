package com.mervyn.miforeman.client;

import net.minecraft.resources.ResourceLocation;

/**
 * Shared client-side display formatting -- pulled out after {@code formatId} ended up
 * copy-pasted into 5 different GUI classes (see .claude/plans/gleaming-mapping-waffle.md).
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

    private DisplayFormat() {
    }
}
