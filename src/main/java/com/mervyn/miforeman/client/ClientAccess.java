package com.mervyn.miforeman.client;

import com.mervyn.miforeman.client.gui.ClipboardScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

public class ClientAccess {
    public static void openClipboardScreen(ItemStack stack) {
        Minecraft.getInstance().setScreen(new ClipboardScreen(stack));
    }

    public static void handleLiveMonitoring(com.mervyn.miforeman.network.LiveMonitoringPayload payload) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof ClipboardScreen clipScreen) {
            clipScreen.updateLiveMonitoring(payload.machines());
        }
    }

    public static void handleScanResult(com.mervyn.miforeman.network.ScanResultPayload payload) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof ClipboardScreen clipScreen) {
            clipScreen.updateScanResults(payload.candidates());
        }
    }
}
