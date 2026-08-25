package com.mervyn.miforeman.compat.emi;

import net.neoforged.fml.ModList;

/**
 * Whether EMI is installed. Uses only NeoForge's {@link ModList}, so unlike everything else in
 * this package it's safe to call from EMI-agnostic code (e.g. to gate the drop-hint icons in
 * {@link com.mervyn.miforeman.client.gui.ClipboardScreen}) even when EMI's jar is absent.
 */
public final class EmiCompat {
    private static final String EMI_MOD_ID = "emi";

    public static boolean isLoaded() {
        return ModList.get().isLoaded(EMI_MOD_ID);
    }

    private EmiCompat() {
    }
}
