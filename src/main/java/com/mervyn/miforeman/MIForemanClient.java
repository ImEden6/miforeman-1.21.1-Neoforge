package com.mervyn.miforeman;

import com.mervyn.miforeman.client.ClientConfig;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = MIForeman.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = MIForeman.MODID, value = Dist.CLIENT)
public class MIForemanClient {
    public MIForemanClient(ModContainer container) {
        // Registered here so a dedicated server never creates the client config file.
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        MIForeman.LOGGER.info("HELLO FROM CLIENT SETUP");
        MIForeman.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }
}
