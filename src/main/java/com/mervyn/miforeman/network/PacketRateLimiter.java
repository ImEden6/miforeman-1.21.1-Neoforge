package com.mervyn.miforeman.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player cooldown for packet handlers that trigger expensive main-thread work
 * (recipe plan recompute, chunk-radius machine scan). An internal safety net against a
 * modified client spamming a packet to cause tick lag -- not a user-facing tunable, so each
 * handler just owns its own instance with a hardcoded interval.
 */
public final class PacketRateLimiter {
    private final long minIntervalTicks;
    private final Map<UUID, Long> lastProcessedTick = new HashMap<>();

    public PacketRateLimiter(long minIntervalTicks) {
        this.minIntervalTicks = minIntervalTicks;
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
}
