package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.DenialCursor;
import com.github.oinsio.gnomish.sandbox.DenialRead;
import com.github.oinsio.gnomish.sandbox.DenialRestoration;
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
final class GuardDenialReads {

    private static final Logger log = LoggerFactory.getLogger(GuardDenialReads.class);

    private static final int LOG_TAIL_LINES = 1000;

    private final DockerCli docker;
    private final String key;

    /** The daemon-side lower bound of the next read — null means "from container start" (D3). */
    private @Nullable String since;

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
     */
    synchronized Optional<DenialCursor> cursor() {
        String position = since;
        if (position == null) {
            return Optional.empty();
        }
        String source = sourceId();
        return source == null ? Optional.empty() : Optional.of(new DenialCursor(source, position));
    }

    /** Invalidates the cached container id: a recreated guard is a different denial source. */
    synchronized void sourceRecreated() {
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
     */
    synchronized DenialRead read() {
        String applied = restored.positionFor(this::sourceId);
        if (applied != null) {
            since = applied;
        }
        String window = since;
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
            return new DenialRead(restored.owedLoss(), cursor());
        }
        if (!logs.ok()) {
            log.warn(
                    OperatorEvent.GUARD_DENIAL_LOG_READ_FAILED.head() + "could not read egress guard log for {}: {}",
                    key,
                    LogText.forLog(logs.stderr()));
            return new DenialRead(restored.owedLoss(), cursor());
        }
        List<Denial> denials = restored.owedLoss();
        if (GuardLogCursor.saturated(logs.stdout(), LOG_TAIL_LINES)) {
            log.warn(
                    OperatorEvent.GUARD_DENIAL_TAIL_WINDOW_FULL.head()
                            + "egress guard log read for {} filled its {}-line tail window; older lines of this"
                            + " window were dropped before parsing and are not in the findings (NFR-O1)",
                    key,
                    LOG_TAIL_LINES);
            denials.add(DenialLossMarker.tailWindowFull(key, LOG_TAIL_LINES, window));
        }
        String advanced = GuardLogCursor.advance(logs.stdout());
        if (advanced != null) {
            since = advanced;
        }
        // One source resolution per read, and the same one for both uses: the position is
        // committable exactly when the source is identifiable, so a read that cannot name its
        // source stamps no identity either — both degrade together, and the daemon is probed once.
        Optional<DenialCursor> position = cursor();
        denials.addAll(restored.merge(
                GuardDenialLog.denials(key, position.map(DenialCursor::source).orElse(null), logs.stdout())));
        return new DenialRead(denials, position);
    }
}
