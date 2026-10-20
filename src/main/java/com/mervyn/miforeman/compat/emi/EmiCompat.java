package com.mervyn.miforeman.compat.emi;

import net.neoforged.fml.ModList;

/**
 * Uses only NeoForge's ModList, so unlike the rest of this package it's safe to call when EMI is
 * absent.
 */
public final class EmiCompat {
    private static final String EMI_MOD_ID = "emi";

    public static boolean isLoaded() {
        return ModList.get().isLoaded(EMI_MOD_ID);
    }

    private EmiCompat() {
    }
}
