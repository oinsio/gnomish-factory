package com.github.oinsio.gnomish.serveobservability.writer;

import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.serveobservability.InstanceInfo;
import com.github.oinsio.gnomish.serveobservability.LifecycleLine;
import com.github.oinsio.gnomish.serveobservability.LifecycleLineAssembler;
import java.io.IOException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The serve startup/shutdown write point for the ledger's {@code lifecycle} line
 * (design D6, FR12): {@link #writeStarted()} and {@link #writeStopped(String)}
 * assemble the line via {@link LifecycleLineAssembler} and append it through the
 * shared {@link RotatingLedgerAppender}, mirroring {@link TaskOutcomeLedgerWriter}'s
 * shape (design D6): a small writer assembling a line and appending it.
 *
 * <p>Write failures never propagate (NFR-R1): an {@link IOException} from the
 * appender is logged and swallowed, exactly as an observability write must never
 * crash the daemon.
 *
 * <p>Implements FR12, NFR-R1, D6 of add-serve-observability.
 */
public final class LifecycleLedgerWriter {

    private static final Logger log = LoggerFactory.getLogger(LifecycleLedgerWriter.class);

    private final RotatingLedgerAppender appender;
    private final InstanceInfo instance;
    private final Clock clock;

    /**
     * @param appender the shared ledger append point every line is written through; never null
     * @param instance this factory instance's identity, carried on every written line; never null
     * @param clock supplies the event's {@code at} instant on every write; never null
     */
    public LifecycleLedgerWriter(RotatingLedgerAppender appender, InstanceInfo instance, Clock clock) {
        this.appender = appender;
        this.instance = instance;
        this.clock = clock;
    }

    /** Writes a {@code started} lifecycle line for this process. */
    public void writeStarted() {
        append(LifecycleLineAssembler.started(instance, clock.instant()));
    }

    /**
     * Writes a {@code stopped} lifecycle line for this process carrying {@code reason}.
     *
     * @param reason why the daemon stopped (e.g. {@code "sigterm"}, {@code "drainComplete"});
     *     never blank
     */
    public void writeStopped(String reason) {
        append(LifecycleLineAssembler.stopped(instance, clock.instant(), reason));
    }

    private void append(LifecycleLine line) {
        try {
            appender.append(line);
        } catch (IOException e) {
            log.error(
                    OperatorEvent.LIFECYCLE_LEDGER_APPEND_FAILED.head() + "failed to append lifecycle ledger line ({})",
                    line.event(),
                    e);
        }
    }
}
