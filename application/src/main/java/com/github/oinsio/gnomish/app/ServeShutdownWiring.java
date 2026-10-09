package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.serve.DrainReport;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.ServeShutdown;
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner;
import com.github.oinsio.gnomish.logtext.ShutdownPhase;
import com.github.oinsio.gnomish.serveobservability.RunSummaryAccumulator;
import com.github.oinsio.gnomish.status.AnchorLog;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two ways {@link ServeCommand#run} can drive its assembled {@link FeedAutomaton} to
 * completion, each registering the SAME JVM shutdown hook shape around a {@link ServeShutdown}
 * (FR11, design D9): one hook covers a signal on the forever-loop path and the normal exit on the
 * drain path ({@link ServeShutdown} says why a pass over an already-empty ledger is safe). It holds
 * no state of its own.
 *
 * <p><b>The hook owns the whole teardown order</b> (design D6 of harden-logging-observability):
 * mark the {@link ShutdownPhase}, drain, finalize the observability lifecycle, then hand off to
 * {@link OrderedExit} for the context close and the logging stop. Owning the last two rather than
 * leaving them to the framework is what keeps a terminal slot line written mid-drain in the log
 * file — the async FILE appender is flushed by the logging stop, and nothing may stop it while a
 * slot is still finishing. Both entry points claim the stop via {@link
 * OrderedExit#reserveSignalOwner()} at <em>registration</em> time, standing the composition root's
 * generic signal hook down before any signal can arrive, and a second pass is a no-op (NFR-R1).
 *
 * <p><b>The page renders last</b> (design D9 of supervise-daemon-loops-and-embed-dashboard): every
 * finalize — the drain body's and both hooks' — goes through {@link #finalizeStopped}, which writes
 * the {@code stopped} snapshot and then, when the dashboard is on, renders the page from it once
 * more, so the operator's open page says the daemon stopped (FR11, UX2). Both steps are idempotent,
 * so whichever caller arrives second changes nothing.
 *
 * <p>Implements FR10, FR11, NFR-O2, M3, D9 of add-factory-serve; FR9, NFR-R1 of
 * harden-logging-observability; FR11, UX2 of supervise-daemon-loops-and-embed-dashboard.
 */
final class ServeShutdownWiring {

    private static final Logger log = LoggerFactory.getLogger(ServeShutdownWiring.class);

    /** The named thread the forever-loop feed automaton runs on (FR11, D9). */
    static final String FEED_THREAD_NAME = "gnomish-serve-feed";

    /** The JVM shutdown hook thread name (FR11, D9): covers both SIGTERM and normal exit. */
    static final String SHUTDOWN_HOOK_THREAD_NAME = "gnomish-serve-shutdown";

    /** The lifecycle reason of a drain that ran to completion (FR12, FR13 of add-serve-observability). */
    static final String DRAIN_COMPLETE_REASON = "drainComplete";

    /**
     * The lifecycle reason of a stop the operating system asked for — named after the mechanism,
     * because the hook runs alike for SIGINT and SIGTERM and cannot tell which arrived (task 3.4 of
     * harden-logging-observability). Opaque text to the snapshot's and the ledger's readers.
     */
    static final String SIGNAL_REASON = "signal";

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
     * line), and finalizes with reason {@code "drainComplete"} — a no-op if the shutdown hook
     * (which the JVM runs on every exit) already finalized first.
     *
     * <p>The hook picks its reason from a {@code drainCompleted} flag set once {@link
     * FeedAutomaton#drain} returns: a signal that lands mid-drain finalizes with {@link
     * #SIGNAL_REASON}, so the {@code stopped} snapshot records an interrupted drain (FR12, FR13, UX4).
     */
    static void runDrain(
            TakeSlotRunner slotRunner,
            FeedAutomaton automaton,
            ServeShutdown shutdown,
            ObservabilityWiring observability,
            Optional<DashboardWatch> dashboard)
            throws InterruptedException {
        runDrain(slotRunner, automaton, shutdown, observability, dashboard, Runtime.getRuntime()::addShutdownHook);
    }

    /**
     * As the overload above, with the hook registration seamed behind {@code hookRegistrar} so
     * specs capture and drive the hook body without touching the real {@link Runtime}.
     */
    static void runDrain(
            TakeSlotRunner slotRunner,
            FeedAutomaton automaton,
            ServeShutdown shutdown,
            ObservabilityWiring observability,
            Optional<DashboardWatch> dashboard,
            ShutdownHookRegistrar hookRegistrar)
            throws InterruptedException {
        DrainReport report = new DrainReport();
        slotRunner.attachDrainReport(report);
        RunSummaryAccumulator accumulator = new RunSummaryAccumulator();
        slotRunner.attachRunSummaryAccumulator(accumulator);
        Instant drainStartedAt = observability.now();
        AtomicBoolean drainCompleted = new AtomicBoolean();
        OrderedExit.reserveSignalOwner();
        hookRegistrar.register(new Thread(
                () -> {
                    ShutdownPhase.begin();
                    String reason = drainCompleted.get() ? DRAIN_COMPLETE_REASON : SIGNAL_REASON;
                    // FR2 of harden-logging-observability: the stopping anchor opens the teardown,
                    // so every line the sequence still writes is bracketed by it — and it names the
                    // same reason the final snapshot records, so log and snapshot never disagree.
                    AnchorLog.serveStopping(reason);
                    shutdown.shutdown(null);
                    finalizeStopped(observability, dashboard, reason);
                    OrderedExit.closeAndStopLogging();
                },
                SHUTDOWN_HOOK_THREAD_NAME));
        observability.beginDraining();
        automaton.drain();
        drainCompleted.set(true);
        observability.beginStopping();
        observability.newRunSummaryLedgerWriter().write(accumulator, drainStartedAt);
        finalizeStopped(observability, dashboard, DRAIN_COMPLETE_REASON);
        log.info("gnomish serve --drain finished: {}", report.summary());
    }

    /**
     * FR11, D9: starts the forever loop on {@link #FEED_THREAD_NAME} so the shutdown hook can
     * interrupt it, registers that hook, then waits for the feed thread to stop; {@link
     * #runFeedLoop} absorbs the interrupt, so a requested stop is a success, not a thrown {@link
     * InterruptedException} (design D7).
     *
     * <p>FR4, FR12 of add-serve-observability: the hook drives {@code observability} through
     * {@code draining} and {@code stopping}, then finalizes with {@link #SIGNAL_REASON} before
     * {@link OrderedExit#closeAndStopLogging()} closes the context and flushes the log file.
     */
    static void runForever(
            FeedAutomaton automaton,
            ServeShutdown shutdown,
            FeedAutomatonStarter starter,
            ObservabilityWiring observability,
            Optional<DashboardWatch> dashboard)
            throws InterruptedException {
        runForever(automaton, shutdown, starter, observability, dashboard, Runtime.getRuntime()::addShutdownHook);
    }

    /**
     * As the overload above, with the hook registration seamed behind {@code hookRegistrar} so
     * specs capture and drive the hook body without touching the real {@link Runtime}.
     */
    static void runForever(
            FeedAutomaton automaton,
            ServeShutdown shutdown,
            FeedAutomatonStarter starter,
            ObservabilityWiring observability,
            Optional<DashboardWatch> dashboard,
            ShutdownHookRegistrar hookRegistrar)
            throws InterruptedException {
        Thread feedThread = new Thread(() -> runFeedLoop(automaton, starter), FEED_THREAD_NAME);
        OrderedExit.reserveSignalOwner();
        hookRegistrar.register(new Thread(
                () -> {
                    ShutdownPhase.begin();
                    AnchorLog.serveStopping(SIGNAL_REASON);
                    observability.beginDraining();
                    shutdown.shutdown(feedThread);
                    observability.beginStopping();
                    finalizeStopped(observability, dashboard, SIGNAL_REASON);
                    OrderedExit.closeAndStopLogging();
                },
                SHUTDOWN_HOOK_THREAD_NAME));
        feedThread.start();
        feedThread.join();
    }

    /** The seam over {@code Runtime.getRuntime()::addShutdownHook} specs capture the hook through. */
    @FunctionalInterface
    interface ShutdownHookRegistrar {
        void register(Thread hook);
    }

    /** The final {@code stopped} snapshot, then the page rendered from it (D9, FR11, UX2). */
    private static void finalizeStopped(
            ObservabilityWiring observability, Optional<DashboardWatch> dashboard, String reason) {
        observability.finalizeStopped(reason);
        dashboard.ifPresent(DashboardWatch::stopAndRenderFinal);
    }

    private static void runFeedLoop(FeedAutomaton automaton, FeedAutomatonStarter starter) {
        try {
            starter.start(automaton);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
