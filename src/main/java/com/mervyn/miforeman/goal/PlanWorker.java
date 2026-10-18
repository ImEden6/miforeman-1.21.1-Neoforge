package com.mervyn.miforeman.goal;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Computes plans off the main thread and hands each result back on it, dropping any result a
 *  newer request has already replaced. Only touched from the main thread. */
public final class PlanWorker {
    private final Executor background;
    private final Executor main;
    // Written only on the main thread; read by the background thread to skip superseded requests.
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

    /** Drops whatever is still computing, e.g. when the screen closes. */
    public void cancel() {
        delivered = ++latest;
    }
}
