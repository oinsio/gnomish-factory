package com.github.oinsio.gnomish.serveobservability.writer;

import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.serveobservability.InstanceInfo;
import com.github.oinsio.gnomish.serveobservability.RunSummaryAccumulator;
import com.github.oinsio.gnomish.serveobservability.RunSummaryLine;
import com.github.oinsio.gnomish.serveobservability.RunSummaryLineAssembler;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The drain-completion write point for the ledger's {@code runSummary} line (design D6, FR13):
 * {@link #write(RunSummaryAccumulator, Instant)} assembles the line via {@link
 * RunSummaryLineAssembler} — reading the drain run's {@link RunSummaryAccumulator} totals exactly
 * once, never the ledger (design D5) — and appends it through the shared {@link
 * RotatingLedgerAppender}, mirroring {@link TaskOutcomeLedgerWriter}/{@link
 * LifecycleLedgerWriter}'s shape: a small writer assembling a line and appending it.
 *
 * <p>Called at most once per drain run, only from the drain completion path — never from a
 * standing-mode stop (FR13's "standing mode SHALL NOT write {@code runSummary} on any stop"):
 * unlike {@link LifecycleLedgerWriter}, which every serve invocation constructs, this writer is
 * only ever constructed and invoked on the {@code --drain} path.
 *
 * <p>Write failures never propagate (NFR-R1): an {@link IOException} from the appender is logged
 * and swallowed, exactly as an observability write must never crash the daemon.
 *
 * <p>Implements FR13, NFR-R1, D6 of add-serve-observability.
 */
public final class RunSummaryLedgerWriter {

    private static final Logger log = LoggerFactory.getLogger(RunSummaryLedgerWriter.class);

    private final RotatingLedgerAppender appender;
    private final InstanceInfo instance;
    private final Clock clock;

    /**
     * @param appender the shared ledger append point every line is written through; never null
     * @param instance this factory instance's identity, carried on the written line; never null
     * @param clock supplies the line's {@code finishedAt} on write; never null
     */
    public RunSummaryLedgerWriter(RotatingLedgerAppender appender, InstanceInfo instance, Clock clock) {
        this.appender = appender;
        this.instance = instance;
        this.clock = clock;
    }

    /**
     * Writes the drain run's single {@code runSummary} line, aggregating {@code accumulator}'s
     * totals over {@code [startedAt, now)}.
     *
     * @param accumulator the drain run's in-memory accumulator; never null
     * @param startedAt when the drain run started; never null
     */
    public void write(RunSummaryAccumulator accumulator, Instant startedAt) {
        RunSummaryLine line = RunSummaryLineAssembler.assemble(instance, startedAt, clock.instant(), accumulator);
        try {
            appender.append(line);
        } catch (IOException e) {
            log.error(
                    OperatorEvent.RUN_SUMMARY_LEDGER_APPEND_FAILED.head() + "failed to append runSummary ledger line",
                    e);
        }
    }
}
