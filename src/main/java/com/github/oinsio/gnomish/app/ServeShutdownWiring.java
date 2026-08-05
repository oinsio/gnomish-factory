package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.serve.DrainReport;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.ServeShutdown;
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner;
import com.github.oinsio.gnomish.serveobservability.RunSummaryAccumulator;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two ways {@link ServeCommand#run} can drive its assembled {@link FeedAutomaton} to
 * completion, each registering the SAME JVM shutdown hook shape around a {@link ServeShutdown}
 * (FR11, design D9): a single hook safely covers SIGTERM on the forever-loop path and the
 * ordinary post-drain normal exit on the drain path (see {@link ServeShutdown}'s own Javadoc for
 * why a no-op flag/wait/kill on an already-empty ledger is safe). Extracted purely to keep {@link
 * ServeCommand} within the file-size limit (process-invariants.md) — holds no state of its own.
 *
 * <p>Implements FR10, FR11, NFR-O2, M3, D9 of add-factory-serve.
 */
final class ServeShutdownWiring {

    private static final Logger log = LoggerFactory.getLogger(ServeShutdownWiring.class);

    /** The named thread the forever-loop feed automaton runs on (FR11, D9). */
    static final String FEED_THREAD_NAME = "gnomish-serve-feed";

    /** The JVM shutdown hook thread name (FR11, D9): covers both SIGTERM and normal exit. */
    static final String SHUTDOWN_HOOK_THREAD_NAME = "gnomish-serve-shutdown";

    private ServeShutdownWiring() {}

    /**
     * FR10, NFR-O2, M3: attaches a fresh closing-report sink, registers the shutdown hook with a
     * {@code null} feed thread (drain runs on the calling thread — nothing to interrupt, and by
     * the time the hook could fire, drain has already emptied every slot itself), drains to
     * completion, and logs the summary — a plain exit 0 (design D7).
     *
     * <p>FR4, FR12, FR13 of add-serve-observability: also transitions {@code observability}
     * through {@code draining} then {@code stopping}, attaches a fresh {@link
     * RunSummaryAccumulator} to {@code slotRunner} so the drain run's {@code runSummary} ledger
     * line can be built (design D6; standing mode never attaches one, so it never writes this
     * line), and finalizes with reason {@code "drainComplete"} — a no-op if the registered
     * shutdown hook already reached {@link ObservabilityWiring#finalizeStopped} first (the JVM
     * runs the hook on every exit, including this one's own normal return).
     *
     * <p>The hook picks its own reason from a {@code drainCompleted} flag the main body sets once
     * {@link FeedAutomaton#drain} returns: on the ordinary post-drain exit the flag is set, so the
     * hook (were it to win the finalize) would still say {@code "drainComplete"}; but on a SIGTERM
     * that lands mid-drain the flag is still clear and the main body may never reach its own
     * {@link ObservabilityWiring#finalizeStopped}/{@code runSummary} write, so the hook finalizes
     * with reason {@code "sigterm"} — the final {@code stopped} snapshot then truthfully records an
     * interrupted drain rather than a misleading {@code "drainComplete"} (FR12, FR13, UX4).
     */
    static void runDrain(
            TakeSlotRunner slotRunner,
            FeedAutomaton automaton,
            ServeShutdown shutdown,
            ObservabilityWiring observability)
            throws InterruptedException {
        runDrain(slotRunner, automaton, shutdown, observability, Runtime.getRuntime()::addShutdownHook);
    }

    /**
     * Same as {@link #runDrain(TakeSlotRunner, FeedAutomaton, ServeShutdown, ObservabilityWiring)},
     * but with the JVM shutdown-hook registration seamed behind {@code hookRegistrar} so tests can
     * verify the hook is registered (and drive its body) without touching the real {@link Runtime}.
     */
    static void runDrain(
            TakeSlotRunner slotRunner,
            FeedAutomaton automaton,
            ServeShutdown shutdown,
            ObservabilityWiring observability,
            ShutdownHookRegistrar hookRegistrar)
            throws InterruptedException {
        DrainReport report = new DrainReport();
        slotRunner.attachDrainReport(report);
        RunSummaryAccumulator accumulator = new RunSummaryAccumulator();
        slotRunner.attachRunSummaryAccumulator(accumulator);
        Instant drainStartedAt = observability.now();
        AtomicBoolean drainCompleted = new AtomicBoolean();
        hookRegistrar.register(new Thread(
                () -> {
                    shutdown.shutdown(null);
                    observability.finalizeStopped(drainCompleted.get() ? "drainComplete" : "sigterm");
                },
                SHUTDOWN_HOOK_THREAD_NAME));
        observability.beginDraining();
        automaton.drain();
        drainCompleted.set(true);
        observability.beginStopping();
        observability.newRunSummaryLedgerWriter().write(accumulator, drainStartedAt);
        observability.finalizeStopped("drainComplete");
        log.info("gnomish serve --drain finished: {}", report.summary());
    }

    /**
     * FR11, D9: starts the forever loop on {@link #FEED_THREAD_NAME} so the shutdown hook (which
     * runs on a JVM-spawned thread of its own on SIGTERM) can interrupt it, registers that hook,
     * then waits for the feed thread to actually stop before returning. An ordinary SIGTERM stop
     * therefore no longer surfaces as a thrown {@link InterruptedException} out of this method —
     * {@link #runFeedLoop} absorbs the interrupt on the feed thread itself (design D7: a
     * requested stop is a success, not a failure).
     *
     * <p>FR4, FR12 of add-serve-observability: the shutdown hook also drives {@code observability}
     * through {@code draining} (before the SIGTERM sequence stops claiming/awaits the grace
     * window), {@code stopping} (once it returns), then finalizes with reason {@code "sigterm"} —
     * the final {@code stopped} snapshot and ledger line.
     */
    static void runForever(
            FeedAutomaton automaton,
            ServeShutdown shutdown,
            FeedAutomatonStarter starter,
            ObservabilityWiring observability)
            throws InterruptedException {
        runForever(automaton, shutdown, starter, observability, Runtime.getRuntime()::addShutdownHook);
    }

    /**
     * Same as {@link #runForever(FeedAutomaton, ServeShutdown, FeedAutomatonStarter,
     * ObservabilityWiring)}, but with the JVM shutdown-hook registration seamed behind {@code
     * hookRegistrar} so tests can verify the hook is registered (and drive its body) without
     * touching the real {@link Runtime}.
     */
    static void runForever(
            FeedAutomaton automaton,
            ServeShutdown shutdown,
            FeedAutomatonStarter starter,
            ObservabilityWiring observability,
            ShutdownHookRegistrar hookRegistrar)
            throws InterruptedException {
        Thread feedThread = new Thread(() -> runFeedLoop(automaton, starter), FEED_THREAD_NAME);
        hookRegistrar.register(new Thread(
                () -> {
                    observability.beginDraining();
                    shutdown.shutdown(feedThread);
                    observability.beginStopping();
                    observability.finalizeStopped("sigterm");
                },
                SHUTDOWN_HOOK_THREAD_NAME));
        feedThread.start();
        feedThread.join();
    }

    /**
     * Testability seam (no behavioral change) for {@code Runtime.getRuntime()::addShutdownHook}:
     * lets specs verify hook registration and capture/run the hook body without registering a real
     * JVM shutdown hook.
     */
    @FunctionalInterface
    interface ShutdownHookRegistrar {
        void register(Thread hook);
    }

    private static void runFeedLoop(FeedAutomaton automaton, FeedAutomatonStarter starter) {
        try {
            starter.start(automaton);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
