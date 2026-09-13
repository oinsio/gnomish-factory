package com.github.oinsio.gnomish.serveobservability.writer;

import com.github.oinsio.gnomish.app.serve.RemoteOutageClosedOutage;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.serveobservability.InstanceInfo;
import com.github.oinsio.gnomish.serveobservability.RemoteOutageLine;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The daemon's write point for the ledger's {@code remoteOutage} line (NFR-O1, NFR-O3 of
 * add-base-ref-resolution, task 7.4): one line per closed outage, mirroring {@link
 * SweepLedgerWriter}'s shape — a small writer assembling a line from the gate's own closed-outage
 * summary and appending it through the shared {@link RotatingLedgerAppender}.
 *
 * <p>Write failures never propagate (NFR-R3): an {@link IOException} from the appender is logged
 * and swallowed — an observability write must never fail the daemon over an already-recovered
 * outage.
 *
 * <p>Implements NFR-O1, NFR-O3, NFR-R3 of add-base-ref-resolution.
 */
public final class RemoteOutageLedgerWriter implements Consumer<RemoteOutageClosedOutage> {

    private static final Logger log = LoggerFactory.getLogger(RemoteOutageLedgerWriter.class);

    private final RotatingLedgerAppender appender;
    private final InstanceInfo instance;

    /**
     * @param appender the shared ledger append point this writer's line is written through; never
     *     null
     * @param instance this factory instance's identity, carried on the written line; never null
     */
    public RemoteOutageLedgerWriter(RotatingLedgerAppender appender, InstanceInfo instance) {
        this.appender = appender;
        this.instance = instance;
    }

    /**
     * Appends one {@code remoteOutage} line for {@code outage}. Called by {@code
     * RemoteOutageGate} exactly once per closed outage.
     *
     * @param outage the closed outage's summary; never null
     */
    @Override
    public void accept(RemoteOutageClosedOutage outage) {
        try {
            appender.append(new RemoteOutageLine(
                    instance,
                    outage.target(),
                    outage.openedAt(),
                    outage.closedAt(),
                    Duration.between(outage.openedAt(), outage.closedAt()),
                    outage.probeCount(),
                    outage.releasedClaims(),
                    outage.lastError()));
        } catch (IOException e) {
            log.error(
                    OperatorEvent.REMOTE_OUTAGE_LEDGER_APPEND_FAILED.head()
                            + "failed to append remoteOutage ledger line",
                    e);
        }
    }
}
