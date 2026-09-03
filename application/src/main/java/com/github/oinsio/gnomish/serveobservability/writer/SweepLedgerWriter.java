package com.github.oinsio.gnomish.serveobservability.writer;

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickListener;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickRecord;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdict;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictListener;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.serveobservability.InstanceInfo;
import com.github.oinsio.gnomish.serveobservability.LedgerLine;
import com.github.oinsio.gnomish.serveobservability.SweepActionLine;
import com.github.oinsio.gnomish.serveobservability.SweepCounts;
import com.github.oinsio.gnomish.serveobservability.SweepTickLine;
import java.io.IOException;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The daemon's sweep write point for the ledger's {@code sweepAction} and {@code sweepTick} lines
 * (NFR-O2 of add-serve-sandbox-lifecycle), mirroring {@link LifecycleLedgerWriter}'s shape: a
 * small writer assembling a line and appending it through the shared {@link
 * RotatingLedgerAppender}.
 *
 * <p>Only the three acting categories become their own line; checked-alive, kept-under-threshold
 * and skipped-no-verdict objects are dropped here and survive only as counts on the tick line, so
 * an hourly sweep over a busy host costs one line per tick rather than one per object.
 *
 * <p>Write failures never propagate (NFR-R3): an {@link IOException} from the appender is logged
 * and swallowed — an observability write must never fail a sweep tick that already acted on real
 * Docker objects.
 *
 * <p>Implements NFR-O2, NFR-R3 of add-serve-sandbox-lifecycle.
 */
public final class SweepLedgerWriter implements SweepVerdictListener, SweepTickListener {

    private static final Logger log = LoggerFactory.getLogger(SweepLedgerWriter.class);

    private final RotatingLedgerAppender appender;
    private final InstanceInfo instance;
    private final Clock clock;

    /**
     * @param appender the shared ledger append point every line is written through; never null
     * @param instance this factory instance's identity, carried on every written line; never null
     * @param clock supplies each line's {@code at} instant; never null
     */
    public SweepLedgerWriter(RotatingLedgerAppender appender, InstanceInfo instance, Clock clock) {
        this.appender = appender;
        this.instance = instance;
        this.clock = clock;
    }

    @Override
    public void onVerdict(SweepVerdict verdict) {
        if (!isAction(verdict.category())) {
            return;
        }
        append(new SweepActionLine(
                instance,
                clock.instant(),
                verdict.objectName(),
                verdict.role(),
                verdict.mode(),
                verdict.taskKey(),
                verdict.category(),
                verdict.reason(),
                verdict.age()));
    }

    @Override
    public void onTickCompleted(SweepTickRecord record) {
        append(new SweepTickLine(instance, record.tickAt(), SweepCounts.of(record.counts())));
    }

    private static boolean isAction(SweepVerdictCategory category) {
        return category == SweepVerdictCategory.STOPPED_ORPHAN
                || category == SweepVerdictCategory.DISPOSED_AGED
                || category == SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE;
    }

    private void append(LedgerLine line) {
        try {
            appender.append(line);
        } catch (IOException e) {
            log.error(OperatorEvent.SWEEP_LEDGER_APPEND_FAILED.head() + "failed to append sweep ledger line", e);
        }
    }
}
