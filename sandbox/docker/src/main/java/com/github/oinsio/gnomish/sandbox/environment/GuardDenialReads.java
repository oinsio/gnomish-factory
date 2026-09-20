package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.DenialCursor;
import com.github.oinsio.gnomish.sandbox.DenialRead;
import com.github.oinsio.gnomish.sandbox.DenialRestoration;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The denial read-back of one {@link EgressGuard} (NFR-O1, D3 of
 * fix-denial-report-attachment): the daemon-side {@code --since} cursor, the
 * bounded log read it drives, and the durable cursor a resume hands back.
 * Extracted from {@link EgressGuard} for file size; the guard keeps the
 * container lifecycle.
 *
 * <p>Consecutive reads return disjoint slices, so a round asking at its close is
 * told its own denials and never an earlier round's. The cursor is a daemon
 * timestamp, immune to in-box clock skew; a failed read leaves it where it was,
 * so nothing is lost to a transient docker outage.
 *
 * <p>Across processes the cursor travels through {@code state.json} (FR5): the
 * guard container outlives a lease, so a resume that reattaches to a surviving
 * container would otherwise re-read its whole log tail and re-attach rounds that
 * already committed their own denials. A restored position is applied only when
 * it names the live container — matched by runtime id, since a position stamped
 * by another machine's daemon clock, or by a container since recreated, could
 * filter real denials out of the report instead.
 *
 * <p>A lost position is therefore cheap, not lossy: the fallback re-read is merged against the
 * identities the branch already records ({@link RecordedDenialMerge}, FR7), and the two losses
 * this class can actually see — a read that filled its tail window, and a committed position
 * whose source is gone — are reported in-band as {@link DenialLossMarker} denials rather than as
 * a WARN no reader of the task report will ever see (FR8, design D6).
 *
 * <p>Implements NFR-O1, NFR-R1, FR5 of fix-denial-report-attachment; FR4, FR7, FR8 of
 * fix-denial-attribution-durability.
 */
@UntrustedParser
final class GuardDenialReads {

    private static final Logger log = LoggerFactory.getLogger(GuardDenialReads.class);

    private static final int LOG_TAIL_LINES = 1000;

    private final DockerCli docker;
    private final String key;

    /**
     * The daemon-side lower bound of the next read — null means "from container start" (D3).
     * Volatile: {@link #cursor()} reads it with no lock held (lock-scope.md), while {@link #read()}
     * writes it only from its locked decide/record phases.
     */
    private volatile @Nullable String since;

    /** What a resume brought — the offered position, the recorded identities, the loss they reveal. */
    private final RestoredDenials restored;

    /** The live guard container's identity as a denial source — what stamps every read (FR5, FR7). */
    private final GuardSourceIdentity identity;

    GuardDenialReads(DockerCli docker, String key) {
        this.docker = docker;
        this.key = key;
        this.restored = new RestoredDenials(key);
        this.identity = new GuardSourceIdentity(docker, key);
    }

    /**
     * Accepts what an earlier lease recorded: the position, applied — or rejected — at the first
     * read (FR5), and the identities of the denials committed with it, which every read merges
     * against so a rejected position costs a merge rather than a duplicated report (FR7).
     */
    synchronized void restore(DenialRestoration restoration) {
        restored.restore(restoration);
    }

    /**
     * The position to commit with the attempt this read delimits, paired with the
     * container it was read from. Empty until a read has actually advanced the
     * cursor, or when the container's id cannot be read — a position with no
     * identifiable source is one a later lease must not apply.
     *
     * <p>Deliberately not {@code synchronized} (lock-scope.md): {@link #sourceId()} may run a
     * {@code docker inspect} probe, and this object's monitor is what {@link #restore} and the
     * locked phases of {@link #read()} take, so holding it here would stall a caller with nothing
     * to do with a subprocess behind a read that can run for the whole command timeout. {@code
     * since} is volatile and {@link GuardSourceIdentity#current()} is independently thread-safe, so
     * nothing here needs the lock.
     */
    Optional<DenialCursor> cursor() {
        return cursorFor(sourceId());
    }

    private Optional<DenialCursor> cursorFor(@Nullable String liveSource) {
        String position = since;
        return position == null || liveSource == null
                ? Optional.empty()
                : Optional.of(new DenialCursor(liveSource, position));
    }

    /**
     * Invalidates the cached container id: a recreated guard is a different denial source.
     * Deliberately not {@code synchronized} — see {@link #cursor()}; the invalidation is a single
     * volatile write on {@link GuardSourceIdentity}, nothing this object's monitor protects.
     */
    void sourceRecreated() {
        identity.recreated();
    }

    /** The live guard container's identity as a denial source, or null when it cannot be read. */
    private @Nullable String sourceId() {
        return identity.current();
    }

    /**
     * The denials recorded since the previous read, paired with the position that stands after it
     * (design D7 of fix-denial-attribution-durability); see {@link EgressGuard#readDenials()}.
     * Every arm answers with the position as it stands — an unreadable log leaves the cursor where
     * it was, so the pair a caller commits still delimits exactly the findings beside it.
     *
     * <p>Three phases (lock-scope.md, after CERT LCK09-J): {@link #beginRead} decides the read
     * window under the monitor, the {@code docker logs} subprocess below runs with nothing held —
     * it is bounded by {@code factory.docker-command-timeout}, five minutes by default, the same
     * monitor {@link #cursor}, {@link #restore} and {@link #sourceRecreated} would otherwise be
     * stalled behind for the whole read — and {@link #recordUnreadable}/{@link #recordRead} retake
     * it to apply the answer. The source id is resolved once, up front, unlocked: it is its own
     * possible probe (see {@link GuardSourceIdentity#current()}), and every phase below needs the
     * same value, so resolving it twice would cost a second unnecessary daemon round trip on a
     * cold cache.
     *
     * <p><b>No claim flag, and why</b> (lock-scope.md, items 3 and 5): the three-phase shape needs
     * one only where two callers could both start the unlocked work. They cannot here. A
     * {@code GuardDenialReads} belongs to one {@link EgressGuard}, which belongs to the
     * {@code ContainerEnvironments} of one environment key — one serve slot's lease — and the only
     * caller of {@link EgressGuard#readDenials()} is the round's own thread, at the two ends of
     * {@code ExecutorRoundExecution} (close, or the failure drain), one round at a time. So the
     * reads of one instance are serial, which is also why phase three needs no revalidation: no
     * other thread can have moved {@link #since} or the restored position between the phases.
     * {@link #cursor()}, {@link #restore} and {@link #sourceRecreated} may be called from
     * elsewhere, and that is exactly what the released monitor is for; none of them starts a read.
     * A second reader would mean two overlapping windows over the same log, so a caller added
     * outside that thread adds the claim flag with itself.
     */
    DenialRead read() {
        String liveSource = sourceId();
        String window = beginRead(liveSource);
        DockerResult logs;
        try {
            logs = docker.run(GuardCommands.guardLogs(key, LOG_TAIL_LINES, window));
        } catch (DockerUnavailableException e) {
            // The runtime outage classification (NFR-R1) applies to work the factory still owes;
            // a denial read is pure observability of work already finished, so an unreachable
            // daemon here is silence with the cursor left where it was — never a thrown round.
            log.warn(
                    OperatorEvent.GUARD_DENIAL_LOG_UNREADABLE.head() + "could not read egress guard log for {}",
                    key,
                    e);
            return recordUnreadable(liveSource);
        }
        if (!logs.ok()) {
            log.warn(
                    OperatorEvent.GUARD_DENIAL_LOG_READ_FAILED.head() + "could not read egress guard log for {}: {}",
                    key,
                    logs.stderr().forLog());
            return recordUnreadable(liveSource);
        }
        return recordRead(logs, window, liveSource);
    }

    /** Phase one: consumes a restored position against the live source and answers the window. */
    private synchronized @Nullable String beginRead(@Nullable String liveSource) {
        String applied = restored.positionFor(() -> liveSource);
        if (applied != null) {
            since = applied;
        }
        return since;
    }

    /** Phase three, when the log could not be read: the loss owed plus the position unchanged. */
    private synchronized DenialRead recordUnreadable(@Nullable String liveSource) {
        return new DenialRead(restored.owedLoss(), cursorFor(liveSource));
    }

    /** Phase three: applies the read log — saturation marker, advanced position, merged denials. */
    private synchronized DenialRead recordRead(
            DockerResult logs, @Nullable String window, @Nullable String liveSource) {
        List<Denial> denials = restored.owedLoss();
        // @UntrustedParser warrant (design D11): the guard's log becomes denial findings, a
        //     saturation boolean and a read position — an RFC-3339 instant `GuardLogCursor`
        //     parses off the line. The lines themselves reach a reader only as a `Finding`'s
        //     capped fields, never as raw text.
        if (GuardLogCursor.saturated(logs.stdout().forParsing(), LOG_TAIL_LINES)) {
            log.warn(
                    OperatorEvent.GUARD_DENIAL_TAIL_WINDOW_FULL.head()
                            + "egress guard log read for {} filled its {}-line tail window; older lines of this"
                            + " window were dropped before parsing and are not in the findings (NFR-O1)",
                    key,
                    LOG_TAIL_LINES);
            denials.add(DenialLossMarker.tailWindowFull(key, LOG_TAIL_LINES, window));
        }
        String advanced = GuardLogCursor.advance(logs.stdout().forParsing());
        if (advanced != null) {
            since = advanced;
        }
        Optional<DenialCursor> position = cursorFor(liveSource);
        denials.addAll(restored.merge(GuardDenialLog.denials(
                key,
                position.map(DenialCursor::source).orElse(null),
                logs.stdout().forParsing())));
        return new DenialRead(denials, position);
    }
}
