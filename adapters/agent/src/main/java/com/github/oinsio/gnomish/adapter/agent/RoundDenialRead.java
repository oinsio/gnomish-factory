package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.domain.engine.Denial;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reading a round's egress denials, on the one rule that makes it safe to call from either end
 * of {@link ExecutorRoundExecution}: <b>this read never brings a round down</b>. The port's
 * answer is observability of work already done — a finished round's usage, trace and committed
 * snapshot all exist by the time it is asked, and a failed round's own infrastructure failure is
 * what the engine must escalate on — so a throwing environment is caught, logged and reported as
 * no denials on both sides (NFR-R1 of fix-denial-report-attachment).
 *
 * <p>Its own class rather than two private helpers because that stance is a responsibility, not a
 * pair of calls: the two entry points differ only in which loss the operator is being told about,
 * and keeping them together is what stops one of them from being "improved" into a throwing read.
 *
 * <p>Implements FR3, NFR-R1 of fix-denial-report-attachment; FR1, FR3 of
 * fix-denial-attribution-durability.
 */
final class RoundDenialRead {

    private static final Logger log = LoggerFactory.getLogger(RoundDenialRead.class);

    private RoundDenialRead() {}

    /**
     * The denials of a round that reached its close, or none when the environment cannot answer.
     * The port promises a degraded empty answer for an unreadable log and a guard-less
     * environment, but the round is already finished by this point, so a throwing observability
     * read must not be what discards it. Same best-effort stance as {@link #onFailure}, on the
     * side where there IS an attempt record to carry the result.
     */
    static List<Denial> onFinish(RoundEnvironmentSource.Round round) {
        try {
            return round.environment().readDenials().denials();
        } catch (RuntimeException e) {
            log.warn(
                    OperatorEvent.ROUND_DENIALS_UNREADABLE_ON_FINISH.head()
                            + "could not read the egress denials of a finished round; reporting none",
                    e);
            return List.of();
        }
    }

    /**
     * Drains the denials of a round that died before its close — a {@code roundTimeout} kill, a
     * missing result event, a process that would not start (D1 of fix-denial-report-attachment).
     * Such a round produces no {@code AttemptRecord}, so the caller carries what this returns out
     * on an {@code ExecutorFailure} instead: the engine copies it onto the {@code CannotExecute}
     * escalation, which is the failed round's only place to land denials (FR1 of
     * fix-denial-attribution-durability). Draining is also what keeps them from becoming the NEXT
     * round's report, since the guard's per-round delta cursor advances only on a read and an
     * in-process resume reuses the same environment. The position that read leaves behind is the
     * one the park commits with the escalation these denials land on (FR3 of
     * fix-denial-attribution-durability) — the environment holds it until then, so the drain
     * cannot advance a position past a record that never gets written. Best-effort squared: the
     * read is already best-effort (NFR-R1) and a throw out of it here would mask the
     * infrastructure failure that brought the round down, so it is caught, logged, and reported
     * as no denials.
     *
     * @return the failed round's denials, or an empty list when the environment cannot answer
     */
    static List<Denial> onFailure(RoundEnvironmentSource.Round round) {
        try {
            List<Denial> denials = round.environment().readDenials().denials();
            log.warn(
                    OperatorEvent.ROUND_DENIALS_ORPHANED_ON_FAILURE.head()
                            + "round failed before close; {} egress denial(s) drained onto the escalation: {}",
                    denials.size(),
                    LogText.forLog(destinations(denials)));
            return denials;
        } catch (RuntimeException e) {
            log.warn(
                    OperatorEvent.ROUND_DENIALS_UNREADABLE_ON_FAILURE.head()
                            + "could not read the egress denials of a failed round",
                    e);
            return List.of();
        }
    }

    /**
     * The denied destinations, comma-separated — the finding's own message, which is the one
     * component of a denial that is never absent. Rendered rather than dumping the list, because
     * the host and path a denial names come from the guard's log of what the gnome asked for:
     * attacker-chosen text, which reaches the record only through {@link LogText} (FR6 of
     * harden-logging-observability).
     */
    private static String destinations(List<Denial> denials) {
        return denials.stream().map(d -> d.finding().message()).collect(Collectors.joining(", "));
    }
}
