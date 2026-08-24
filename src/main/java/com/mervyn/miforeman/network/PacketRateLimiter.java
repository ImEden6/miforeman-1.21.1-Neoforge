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

/**
 * Per-player cooldown for packet handlers that trigger expensive main-thread work
 * (recipe plan recompute, chunk-radius machine scan). An internal safety net against a
 * modified client spamming a packet to cause tick lag -- not a user-facing tunable, so each
 * handler just owns its own instance with a hardcoded interval.
 */
public final class PacketRateLimiter {
    // Every instance registers itself here, so Cleanup (below) can evict a disconnected player's
    // entry from all of them without each handler wiring up its own listener. Same cleanup
    // ServerMonitoringManager.onServerStopped already does, so this doesn't leak state either.
    private static final List<PacketRateLimiter> ALL = new CopyOnWriteArrayList<>();

    private final long minIntervalTicks;
    private final Map<UUID, Long> lastProcessedTick = new HashMap<>();

    public PacketRateLimiter(long minIntervalTicks) {
        this.minIntervalTicks = minIntervalTicks;
        ALL.add(this);
    }

    /** Returns true if this call may proceed (and records it); false if it arrived too soon. */
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
            // Singleplayer starts a new server per world, so a stale UUID from a previous
            // world must not linger across the switch.
            for (PacketRateLimiter limiter : ALL) {
                limiter.lastProcessedTick.clear();
            }
        }
    }
}
