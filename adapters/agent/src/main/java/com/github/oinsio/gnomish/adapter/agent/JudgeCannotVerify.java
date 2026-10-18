package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.domain.engine.Verdict;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter.Vote;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The vote of a judge round that cannot verify, and the round's single WARN site (FR5 of
 * harden-logging-observability): the vote's degraded result reaches the engine as a value, so
 * without a line here the infrastructure failure classes of {@link JudgeRoundExecution} (scratch
 * directory, process start, round timeout, wait interrupt, missing result event, drain timeout,
 * drain interrupt, a dead egress guard at either end of the round) would be invisible to the
 * operator. One site, one line — the caller receives the same facts.
 *
 * <p>Built per round, over the round's {@link RoundScratchDirectory} once one exists: every vote
 * built after the CLI was launched carries the tail of its stderr in the details, after what the
 * failure itself said, so the operator reads it at WARN and in the escalation (design D18 of
 * fix-terminal-receipt-convergence, as amended 2026-10-17). Before the directory exists no process
 * ran, and the details are the failure's alone. Split out of {@link JudgeRoundExecution}, which
 * keeps the launch sequence; this class owns what a failure of it becomes.
 *
 * <p>Implements FR5 of harden-logging-observability; FR20 of fix-terminal-receipt-convergence.
 */
final class JudgeCannotVerify {

    private static final Logger log = LoggerFactory.getLogger(JudgeCannotVerify.class);

    /** Shared by both kinds of exit so the two lines cannot drift apart. */
    private static final String CANNOT_VERIFY_LINE = "judge vote cannot verify for criteria {}: {} ({})";

    /** How much of the stderr tail joins the details: room left for the failure's own words. */
    static final int TAIL_EXCERPT_CHARS = 1_500;

    private final VerifyCheck.Judge check;

    private final @Nullable RoundScratchDirectory scratch;

    private JudgeCannotVerify(VerifyCheck.Judge check, @Nullable RoundScratchDirectory scratch) {
        this.check = check;
        this.scratch = scratch;
    }

    /** The exits before the round's scratch directory exists: no process ran, so no tail. */
    static JudgeCannotVerify beforeRound(VerifyCheck.Judge check) {
        return new JudgeCannotVerify(check, null);
    }

    /** The exits of a round whose CLI's stderr goes to {@code scratch}. */
    static JudgeCannotVerify ofRound(VerifyCheck.Judge check, RoundScratchDirectory scratch) {
        return new JudgeCannotVerify(check, scratch);
    }

    /**
     * The guard's liveness probe at a round boundary (design D14 of tighten-spec-contract), on the
     * judge's never-throw contract: a dead guard — or a runtime that cannot say — is a vote that
     * cannot verify. Empty when the guard runs, or when the environment has none (host mode).
     */
    Optional<Vote> ifGuardDead(TaskExecutionEnvironment environment) {
        try {
            environment.probeGuard();
            return Optional.empty();
        } catch (RuntimeException e) {
            // The guard's message names its container and folds in the runtime's own answer, so
            // it leaves as container text, rendered by the carrier's log exit.
            return Optional.of(byThrowable(
                    "the egress guard of the judge box is not running",
                    UntrustedText.container(String.valueOf(e.getMessage())),
                    e));
        }
    }

    /** An exit whose cause is a decision rather than a throwable (timeout, interrupt). */
    Vote byDecision(String reason, UntrustedText details) {
        UntrustedText withTail = withTail(details);
        log.warn(
                OperatorEvent.JUDGE_CANNOT_VERIFY_BY_DECISION.head() + CANNOT_VERIFY_LINE,
                check.criteriaFile(),
                reason,
                withTail.forLog());
        return vote(reason, withTail);
    }

    /** An exit carrying a throwable, which is passed trailing so the stack survives. */
    Vote byThrowable(String reason, UntrustedText details, Throwable cause) {
        UntrustedText withTail = withTail(details);
        log.warn(
                OperatorEvent.JUDGE_CANNOT_VERIFY_BY_THROWABLE.head() + CANNOT_VERIFY_LINE,
                check.criteriaFile(),
                reason,
                withTail.forLog(),
                cause);
        return vote(reason, withTail);
    }

    /**
     * The exception's own words as agent text: what a round's failure says quotes the stream the
     * agent CLI wrote, so it leaves through an exit like every other agent output (design D3, D5
     * of type-untrusted-text).
     */
    static UntrustedText messageOf(RuntimeException e) {
        return UntrustedText.agent(
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    /**
     * The failure's details followed by the stderr tail, both through the log exit first, so the
     * sentence is factory prose quoting two captures that already left their carriers; the
     * details unchanged when no process ran or it wrote no stderr.
     */
    private UntrustedText withTail(UntrustedText details) {
        if (scratch == null) {
            return details;
        }
        UntrustedText tail = scratch.tailForFailure();
        if (tail.isBlank()) {
            return details;
        }
        return UntrustedText.factory(
                details.forLog() + "; agent CLI stderr, last bytes: " + tail.excerpt(TAIL_EXCERPT_CHARS));
    }

    private static Vote vote(String reason, UntrustedText details) {
        return new Vote(new Verdict.CannotVerify(UntrustedText.factory(reason), details), Map.of());
    }
}
