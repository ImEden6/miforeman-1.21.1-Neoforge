package com.mervyn.miforeman;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mervyn.miforeman.command.ForemanCommands;
import com.mervyn.miforeman.goal.RecipeGraphTraverser;
import com.mervyn.miforeman.registry.ModComponents;
import com.mervyn.miforeman.registry.ModItems;
import com.mervyn.miforeman.test.ForemanGameTests;

import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

@Mod(MIForeman.MODID)
public class MIForeman {
    public static final String MODID = "miforeman";
    public static final Logger LOGGER = LogUtils.getLogger();
    public MIForeman(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::registerPackets);

        ModComponents.init(modEventBus);
        ModItems.init(modEventBus);

        NeoForge.EVENT_BUS.register(this);

        ForemanCommands.init();

        modEventBus.addListener(this::addCreative);

        modEventBus.addListener(RegisterGameTestsEvent.class, event -> {
            LOGGER.info("MIForeman: Registering GameTests!");
            event.register(ForemanGameTests.class);
        });

        // Client config is registered in MIForemanClient, so a dedicated server doesn't create an empty client file.
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // The recipe graph caches don't key on this setting, so clear them whenever the config reloads.
        modEventBus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == Config.SPEC) {
                RecipeGraphTraverser.clearGraphCache();
            }
        });
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.FOREMAN_CLIPBOARD_ITEM.get());
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("HELLO from server starting");
    }

    private void registerPackets(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        // Bump whenever a payload's wire format changes, so mismatched client and server are refused at connect.
        var registrar = event.registrar("3");
        registrar.playToServer(
                com.mervyn.miforeman.network.GoalUpdatePayload.TYPE,
                com.mervyn.miforeman.network.GoalUpdatePayload.STREAM_CODEC,
                com.mervyn.miforeman.network.GoalUpdateHandler::handle
        );
        registrar.playToServer(
                com.mervyn.miforeman.network.RequestMonitoringUpdatePayload.TYPE,
                com.mervyn.miforeman.network.RequestMonitoringUpdatePayload.STREAM_CODEC,
                com.mervyn.miforeman.network.MonitoringPacketHandlers::handleRequest
        );
        registrar.playToClient(
                com.mervyn.miforeman.network.LiveMonitoringPayload.TYPE,
                com.mervyn.miforeman.network.LiveMonitoringPayload.STREAM_CODEC,
                com.mervyn.miforeman.network.MonitoringPacketHandlers::handleResponse
        );
        registrar.playToServer(
                com.mervyn.miforeman.network.ScanRequestPayload.TYPE,
                com.mervyn.miforeman.network.ScanRequestPayload.STREAM_CODEC,
                com.mervyn.miforeman.network.ScanPacketHandlers::handleRequest
        );
        registrar.playToClient(
                com.mervyn.miforeman.network.ScanResultPayload.TYPE,
                com.mervyn.miforeman.network.ScanResultPayload.STREAM_CODEC,
                com.mervyn.miforeman.network.ScanPacketHandlers::handleResponse
        );
        registrar.playToClient(
                com.mervyn.miforeman.network.MachineLinkSyncPayload.TYPE,
                com.mervyn.miforeman.network.MachineLinkSyncPayload.STREAM_CODEC,
                com.mervyn.miforeman.network.MachineLinkSyncHandler::handle
        );
    }
}
