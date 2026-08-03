package com.mervyn.miforeman.goal;

import net.minecraft.resources.ResourceLocation;

public record GraphEdge(
    ResourceLocation from,
    ResourceLocation to,
    double rate
) {}
