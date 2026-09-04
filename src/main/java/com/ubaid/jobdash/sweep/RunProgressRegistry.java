package com.ubaid.jobdash.sweep;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared in-memory registry of live run state: a progress snapshot, a cooperative-cancellation
 * flag, and the thread doing the work, keyed by run id. Extracted out of {@link SweepService}
 * (which used to own these three maps directly) so that BOTH {@code SweepService} (LinkedIn) and
 * {@code AtsSweepService} (Greenhouse/Lever/Workday) publish into ONE registry — that is what
 * lets {@code RunController}'s SSE endpoint and cancel/progress lookups work identically
 * regardless of which kind of run is in flight.
 * <p>
 * Behaviour is unchanged from what {@code SweepService} did inline; see {@code SweepServiceTest}
 * for the contract this must keep satisfying.
 */
@Component
public class RunProgressRegistry {

    private final ConcurrentHashMap<Long, SweepProgress> progress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Thread> runThreads = new ConcurrentHashMap<>();

    /** Registers a freshly created run: a fresh (unset) cancel flag and an initial progress snapshot. */
    public void start(long runId, SweepProgress initial) {
        cancelFlags.put(runId, new AtomicBoolean(false));
        progress.put(runId, initial);
    }

    /** Reads the current in-memory progress snapshot for a run, if known to this process. */
    public Optional<SweepProgress> progress(long runId) {
        return Optional.ofNullable(progress.get(runId));
    }

    /** Publishes a new progress snapshot for a run, overwriting any previous one. */
    public void publish(long runId, SweepProgress snapshot) {
        progress.put(runId, snapshot);
    }

    /** The cancel flag for a run, creating a fresh (unset) one if this process hasn't seen the run yet. */
    public AtomicBoolean cancelFlag(long runId) {
        return cancelFlags.computeIfAbsent(runId, id -> new AtomicBoolean(false));
    }

    /** Records which thread is doing a run's work, so {@link #cancel} can interrupt it. */
    public void registerThread(long runId, Thread thread) {
        runThreads.put(runId, thread);
    }

    /** Registers the calling thread as the run's thread, but only if none is already known. */
    public void registerCurrentThreadIfAbsent(long runId) {
        runThreads.putIfAbsent(runId, Thread.currentThread());
    }

    /**
     * Requests cooperative cancellation of an in-flight run: sets a flag the run loop checks at
     * the top of every iteration, and interrupts the run's thread (if known to this process) so
     * a blocked pacing wait unblocks promptly instead of waiting out its full delay.
     *
     * @return true if a cancellation was actually requested (the run was known and not already
     * finished); false otherwise.
     */
    public boolean cancel(long runId) {
        AtomicBoolean flag = cancelFlags.get(runId);
        if (flag == null) {
            return false;
        }
        flag.set(true);
        Thread thread = runThreads.get(runId);
        if (thread != null) {
            thread.interrupt();
        }
        return true;
    }

    /** Releases the bookkeeping (cancel flag, thread reference) for a finished run. The progress snapshot is kept. */
    public void finish(long runId) {
        cancelFlags.remove(runId);
        runThreads.remove(runId);
    }
}
