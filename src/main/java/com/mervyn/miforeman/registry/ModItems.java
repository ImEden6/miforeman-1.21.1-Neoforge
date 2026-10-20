package com.mervyn.miforeman.registry;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.item.ForemanClipboardItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MIForeman.MODID);

    public static final DeferredItem<ForemanClipboardItem> FOREMAN_CLIPBOARD_ITEM = ITEMS.registerItem("foreman_clipboard",
            properties -> new ForemanClipboardItem(properties.stacksTo(1)));

    public static void init(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }

    private ModItems() {}
}
