package com.ubaid.jobdash.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Wires shared infrastructure for the {@code com.ubaid.jobdash.web} package. */
@Configuration
public class WebConfiguration {

    /**
     * Backs every {@code /api/runs/{id}/stream} SSE emitter's periodic progress push. One
     * shared scheduler for all concurrent streams (a handful of runs at most); shut down
     * cleanly when the application context closes so no thread lingers.
     */
    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService sseScheduler() {
        return Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "sse-progress");
            thread.setDaemon(true);
            return thread;
        });
    }
}
