package com.mervyn.miforeman.client;

import com.mervyn.miforeman.client.gui.ClipboardScreen;
import com.mervyn.miforeman.client.gui.MonitoringScreen;
import com.mervyn.miforeman.client.gui.ReviewMachinesScreen;
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
        } else if (screen instanceof MonitoringScreen monScreen) {
            monScreen.updateLiveMonitoring(payload.machines());
        } else if (screen instanceof ReviewMachinesScreen revScreen) {
            revScreen.updateLiveMonitoring(payload.machines());
        }
    }

    public static void handleScanResult(com.mervyn.miforeman.network.ScanResultPayload payload) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof ClipboardScreen clipScreen) {
            clipScreen.updateScanResults(payload.candidates());
        } else if (screen instanceof ReviewMachinesScreen revScreen) {
            revScreen.updateScanResults(payload.candidates());
        }
    }

    public static void handleMachineLinkSync(com.mervyn.miforeman.network.MachineLinkSyncPayload payload) {
        WorldHighlightRenderer.setLinked(payload.pos(), payload.linked());
    }
}
