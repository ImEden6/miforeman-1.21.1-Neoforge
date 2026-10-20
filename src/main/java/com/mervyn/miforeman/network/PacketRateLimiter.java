package com.mervyn.miforeman.network;

import com.mervyn.miforeman.MIForeman;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PacketRateLimiter {
    // Shared so Cleanup can evict a disconnected player from every limiter.
    private static final List<PacketRateLimiter> ALL = new CopyOnWriteArrayList<>();

    private final long minIntervalTicks;
    private final Map<UUID, Long> lastProcessedTick = new HashMap<>();

    public PacketRateLimiter(long minIntervalTicks) {
        this.minIntervalTicks = minIntervalTicks;
        ALL.add(this);
    }

    public boolean tryAcquire(UUID playerId, long currentGameTime) {
        Long last = lastProcessedTick.get(playerId);
        if (last != null && currentGameTime - last < minIntervalTicks) {
            return false;
        }
        lastProcessedTick.put(playerId, currentGameTime);
        return true;
    }

    @EventBusSubscriber(modid = MIForeman.MODID)
    private static final class Cleanup {
        @SubscribeEvent
        public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            UUID playerId = event.getEntity().getUUID();
            for (PacketRateLimiter limiter : ALL) {
                limiter.lastProcessedTick.remove(playerId);
            }
        }

        @SubscribeEvent
        public static void onServerStopped(ServerStoppedEvent event) {
            // Singleplayer starts a new server per world, so clear UUIDs left from the last one.
            for (PacketRateLimiter limiter : ALL) {
                limiter.lastProcessedTick.clear();
            }
        }
    }
}
