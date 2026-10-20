package com.mervyn.miforeman.registry;

import com.mervyn.miforeman.MIForeman;
import com.mervyn.miforeman.goal.ProductionGoal;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModComponents {
    public static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MIForeman.MODID);

    public static final Supplier<DataComponentType<ProductionGoal>> PRODUCTION_GOAL = COMPONENTS.registerComponentType("production_goal",
            builder -> builder.persistent(ProductionGoal.CODEC).networkSynchronized(ProductionGoal.STREAM_CODEC));

    public static void init(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }

    private ModComponents() {}
}
