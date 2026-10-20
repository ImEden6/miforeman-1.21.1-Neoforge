package com.mervyn.miforeman.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;

import java.util.List;

public final class ScanPing {
    private static final int PARTICLES_PER_MACHINE = 12;

    private ScanPing() {
    }

    public static void ping(List<GlobalPos> positions) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || positions.isEmpty()) {
            return;
        }
        RandomSource random = level.getRandom();
        for (GlobalPos pos : positions) {
            // Scans cover only the player's dimension, but a result can arrive after a portal trip.
            if (!pos.dimension().equals(level.dimension())) {
                continue;
            }
            double cx = pos.pos().getX() + 0.5;
            double cy = pos.pos().getY() + 0.5;
            double cz = pos.pos().getZ() + 0.5;
            for (int i = 0; i < PARTICLES_PER_MACHINE; i++) {
                level.addParticle(ParticleTypes.HAPPY_VILLAGER,
                        cx + (random.nextDouble() - 0.5) * 1.2,
                        cy + (random.nextDouble() - 0.5) * 1.2,
                        cz + (random.nextDouble() - 0.5) * 1.2,
                        0.0, 0.05, 0.0);
            }
        }
    }
}
