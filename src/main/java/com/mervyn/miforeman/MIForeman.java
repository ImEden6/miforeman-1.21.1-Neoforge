package com.mervyn.miforeman;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.mervyn.miforeman.command.ForemanCommands;
import com.mervyn.miforeman.registry.ModComponents;
import com.mervyn.miforeman.registry.ModItems;
import com.mervyn.miforeman.test.ForemanGameTests;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(MIForeman.MODID)
public class MIForeman {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "miforeman";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public MIForeman(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPackets);

        // Register custom Data Components and Items
        ModComponents.init(modEventBus);
        ModItems.init(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (MIForeman) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register commands
        ForemanCommands.init();

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register GameTests
        modEventBus.addListener(RegisterGameTestsEvent.class, event -> {
            LOGGER.info("MIForeman: Registering GameTests!");
            event.register(ForemanGameTests.class);
        });

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    // Add the items to creative tabs
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.FOREMAN_CLIPBOARD_ITEM.get());
        }
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }

    private void registerPackets(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
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
    }
}
