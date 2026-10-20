package com.mervyn.miforeman.compat.emi;

import com.mervyn.miforeman.client.gui.EmiTargetPickerScreen;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;

/**
 * EMI finds this on NeoForge by annotation scan, so no service file is needed. Without EMI
 * nothing loads this class, so its dev.emi imports never resolve.
 */
@EmiEntrypoint
public class MiforemanEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        // A bounds provider over the left-side panel keeps EMI's sidebar out of it.
        registry.addScreenBoundsProvider(EmiTargetPickerScreen.class, EmiTargetPickerIntegration::getPanelBounds);
        registry.addDragDropHandler(EmiTargetPickerScreen.class, new EmiTargetPickerDragDropHandler());
    }
}
