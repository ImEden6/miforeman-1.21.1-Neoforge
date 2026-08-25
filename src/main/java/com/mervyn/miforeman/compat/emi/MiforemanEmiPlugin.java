package com.mervyn.miforeman.compat.emi;

import com.mervyn.miforeman.client.gui.EmiTargetPickerScreen;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;

/**
 * Lets a player drag an item/fluid out of EMI's sidebar onto {@link EmiTargetPickerScreen} (opened
 * from {@link com.mervyn.miforeman.client.gui.ClipboardScreen}'s Define Goal step via its "From
 * EMI" button) to set the goal target, instead of typing a resource ID by hand.
 *
 * <p>Discovered by EMI on NeoForge via its mod-file annotation scan (no service-loader or
 * neoforge.mods.toml entry needed) -- if EMI isn't installed, nothing ever asks for this class,
 * so it's simply never loaded and its {@code dev.emi.*} imports never get resolved.
 */
@EmiEntrypoint
public class MiforemanEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        // EmiTargetPickerScreen deliberately occupies only the left side of the window, leaving
        // the right side genuinely free -- so a bounds provider covering just the panel is enough
        // to keep EMI's sidebar out of it. No exclusion area is needed since there's nothing else
        // on the screen that needs separate protection.
        registry.addScreenBoundsProvider(EmiTargetPickerScreen.class, EmiTargetPickerIntegration::getPanelBounds);
        registry.addDragDropHandler(EmiTargetPickerScreen.class, new EmiTargetPickerDragDropHandler());
    }
}
