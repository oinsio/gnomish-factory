package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.serve.SlotLedger;
import com.github.oinsio.gnomish.serveobservability.InstanceInfo;
import com.github.oinsio.gnomish.serveobservability.writer.LifecycleLedgerWriter;
import com.github.oinsio.gnomish.serveobservability.writer.RemoteOutageLedgerWriter;
import com.github.oinsio.gnomish.serveobservability.writer.RotatingLedgerAppender;
import com.github.oinsio.gnomish.serveobservability.writer.RunSummaryLedgerWriter;
import com.github.oinsio.gnomish.serveobservability.writer.SweepLedgerWriter;
import com.github.oinsio.gnomish.serveobservability.writer.TaskOutcomeLedgerWriter;
import java.time.Clock;

/**
 * Every ledger write point one {@code serve} instance owns, built over its one {@link
 * RotatingLedgerAppender} (design D2 of collapse-composition-roots): the {@code lifecycle}, {@code
 * taskOutcome}, {@code sweep} and {@code remoteOutage} writers for the daemon's lifetime, and a
 * fresh {@code runSummary} writer per drain run. Building them all here is what keeps every line
 * of the instance's ledger on the same appender, so each rotates and is retained exactly like the
 * others (NFR-O2 of add-serve-sandbox-lifecycle; NFR-O1, NFR-O3 of add-base-ref-resolution).
 *
 * <p>Implements FR1, FR4 of collapse-composition-roots; FR11, FR12, FR13 of add-serve-observability.
 */
final class LedgerWriters {

    private final RotatingLedgerAppender appender;
    private final InstanceInfo instance;
    private final Clock clock;
    private final LifecycleLedgerWriter lifecycle;
    private final TaskOutcomeLedgerWriter taskOutcome;
    private final SweepLedgerWriter sweep;
    private final RemoteOutageLedgerWriter remoteOutage;

    /**
     * @param appender the instance's one ledger appender every write point shares; never null
     * @param slotLedger the shared slot registry the {@code taskOutcome} lines are recorded
     *     against; never null
     * @param instance this process's identity, carried on every ledger line; never null
     * @param clock the wall-clock time source for every write point; never null
     */
    LedgerWriters(RotatingLedgerAppender appender, SlotLedger slotLedger, InstanceInfo instance, Clock clock) {
        this.appender = appender;
        this.instance = instance;
        this.clock = clock;
        this.lifecycle = new LifecycleLedgerWriter(appender, instance, clock);
        this.taskOutcome = new TaskOutcomeLedgerWriter(slotLedger, appender, instance, clock);
        this.sweep = new SweepLedgerWriter(appender, instance, clock);
        this.remoteOutage = new RemoteOutageLedgerWriter(appender, instance);
    }

    /** The {@code started} / {@code stopped} write point (FR12); never null. */
    LifecycleLedgerWriter lifecycle() {
        return lifecycle;
    }

    /** The {@code taskOutcome} write point every slot attaches to (FR11); never null. */
    TaskOutcomeLedgerWriter taskOutcome() {
        return taskOutcome;
    }

    /** The sweep's ledger write point, both its verdict and its tick sink (NFR-O2); never null. */
    SweepLedgerWriter sweep() {
        return sweep;
    }

    /** The {@code remoteOutage} write point (NFR-O1, NFR-O3 of add-base-ref-resolution); never null. */
    RemoteOutageLedgerWriter remoteOutage() {
        return remoteOutage;
    }

    /** A fresh drain-run {@code runSummary} writer over the shared appender (FR13); never null. */
    RunSummaryLedgerWriter newRunSummary() {
        return new RunSummaryLedgerWriter(appender, instance, clock);
    }
}
