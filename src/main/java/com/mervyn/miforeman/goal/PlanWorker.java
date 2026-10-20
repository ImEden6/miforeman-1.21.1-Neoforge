package com.mervyn.miforeman.goal;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Computes plans off the main thread and hands results back on it, dropping any a newer
 *  request replaced. Call only from the main thread. */
public final class PlanWorker {
    private final Executor background;
    private final Executor main;
    // Written only on the main thread. The background thread reads it to skip stale requests.
    private volatile int latest;
    private int delivered;

    public PlanWorker(Executor background, Executor main) {
        this.background = background;
        this.main = main;
    }

    public <T> void submit(Supplier<T> compute, BiConsumer<@Nullable T, @Nullable Exception> deliver) {
        int request = ++latest;
        background.execute(() -> {
            if (request != latest)
                return;
            T result = null;
            Exception error = null;
            try {
                result = compute.get();
            } catch (Exception e) {
                error = e;
            }
            T finalResult = result;
            Exception finalError = error;
            main.execute(() -> {
                if (request != latest)
                    return;
                delivered = request;
                deliver.accept(finalResult, finalError);
            });
        });
    }

    public boolean pending() {
        return delivered != latest;
    }

    /** Drops any plan still computing, as when the screen closes. */
    public void cancel() {
        delivered = ++latest;
    }
}
