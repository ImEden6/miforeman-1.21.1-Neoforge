package com.mervyn.miforeman.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * A brief particle burst on machines a scan just found, so the player can spot them in the
 * world without opening the review list. Client-only and purely cosmetic.
 */
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
            // Scans only cover the player's own dimension, but a result can land after a portal trip.
            if (!pos.dimension().equals(level.dimension())) {
                continue;
            }
            double cx = pos.pos().getX() + 0.5;
            double cy = pos.pos().getY() + 0.5;
            double cz = pos.pos().getZ() + 0.5;
            for (int i = 0; i < PARTICLES_PER_MACHINE; i++) {
                // Spread over the block's faces, drifting up and out.
                level.addParticle(ParticleTypes.HAPPY_VILLAGER,
                        cx + (random.nextDouble() - 0.5) * 1.2,
                        cy + (random.nextDouble() - 0.5) * 1.2,
                        cz + (random.nextDouble() - 0.5) * 1.2,
                        0.0, 0.05, 0.0);
            }
        }
    }
}
