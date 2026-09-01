package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.logtext.RepeatOccurrence;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The log plane of {@link GithubWorkflowRunPoll}: one line per poll outcome, and the level policy
 * for the one outcome that repeats. The poll itself owns classification; this owns what an
 * operator is told about it, which is why the streak state lives here and not there.
 *
 * <p>An external check is polled every few seconds until its timeout, so a dependency that cannot
 * answer would otherwise write one WARN per tick for the whole verification window. The
 * cannot-verify path therefore reports to a {@link RepeatSuppressor} and logs the edge it gets
 * back — first occurrence and periodic counted roll-ups at WARN, the repetitions in between at
 * DEBUG — and any poll that reaches a verdict closes the streak with one INFO recovery line
 * (FR4, UX3 of harden-logging-observability).
 *
 * <p>The suppressor is handed in rather than built here because it must outlive this object: a
 * poll loop rebuilds the poll (and this) per tick, and the streak is precisely what has to survive
 * between them. Suppression is a log-plane concern only — the {@link PollStatus} the caller
 * receives is unchanged by it, so no verdict depends on how many times this instance has polled.
 *
 * <p>Logs under {@link GithubWorkflowRunPoll}'s own logger name: these lines are about the poll,
 * and an operator filtering by category should not have to know the split exists.
 *
 * <p>Implements NFR-O1, UX1 of add-external-check-github-actions; FR4, UX3 of
 * harden-logging-observability.
 */
final class GithubWorkflowPollLog {

    private static final Logger log = LoggerFactory.getLogger(GithubWorkflowRunPoll.class);
    private static final String NO_RUN = "none";
    private static final String NO_URL = "unavailable";

    /** Namespaces the streak key, which is shared with whatever else the run reports. */
    private static final String KEY_PREFIX = "github-workflow-poll:";

    private final RepeatSuppressor cannotVerifySuppressor;

    GithubWorkflowPollLog(RepeatSuppressor cannotVerifySuppressor) {
        this.cannotVerifySuppressor = cannotVerifySuppressor;
    }

    /**
     * Reports one poll's outcome, with the run id and — when known — the run's platform URL
     * (NFR-O1). A Pass verdict has no field to carry the run URL onward, so for Pass this line is
     * the only place the run link surfaces.
     */
    void outcome(String checkId, String headSha, PollStatus status, @Nullable GithubWorkflowRun matchingRun) {
        String runId = matchingRun == null ? NO_RUN : Long.toString(matchingRun.id());
        String runUrl = matchingRun == null || matchingRun.htmlUrl() == null ? NO_URL : matchingRun.htmlUrl();
        String subject = checkId + "@" + headSha;
        switch (status) {
            case PollStatus.Pass ignored -> {
                announceRecovery(subject);
                log.info("GitHub Actions check {} passed: run {} ({})", subject, runId, runUrl);
            }
            case PollStatus.Fail fail -> {
                announceRecovery(subject);
                log.info(
                        "GitHub Actions check {} failed: run {} ({}), {} finding(s)",
                        subject,
                        runId,
                        runUrl,
                        fail.findings().size());
            }
            case PollStatus.Running ignored -> {
                announceRecovery(subject);
                log.debug("GitHub Actions check {} still running: run {}", subject, runId);
            }
            case PollStatus.CannotVerify cannotVerify -> cannotVerify(subject, cannotVerify.reason());
        }
    }

    /**
     * The cannot-verify edge (FR4): the fault arriving and the periodic reminder are WARN — the
     * operator has to know a check stopped answering — while the ticks in between carry nothing
     * new and stay at DEBUG.
     */
    private void cannotVerify(String subject, String reason) {
        switch (cannotVerifySuppressor.failed(KEY_PREFIX + subject, reason)) {
            case RepeatOccurrence.First first ->
                log.warn("GitHub Actions check {} could not be verified: {}", subject, first.reason());
            case RepeatOccurrence.Repeat repeat ->
                log.debug(
                        "GitHub Actions check {} still cannot be verified ({}x): {}",
                        subject,
                        repeat.count(),
                        repeat.reason());
            case RepeatOccurrence.RollUp rollUp ->
                log.warn(
                        "GitHub Actions check {} could not be verified {}x over {}: {}",
                        subject,
                        rollUp.count(),
                        rollUp.elapsed(),
                        rollUp.reason());
        }
    }

    /**
     * Closes an open cannot-verify streak when a poll reaches a verdict again: the operator was
     * told the check went quiet, so they are told when it came back. A poll that never failed ends
     * no streak and logs nothing.
     */
    private void announceRecovery(String subject) {
        cannotVerifySuppressor
                .recovered(KEY_PREFIX + subject)
                .ifPresent(recovery -> log.info(
                        "GitHub Actions check {} can be verified again after {} failed poll(s) over {}: {}",
                        subject,
                        recovery.occurrences(),
                        recovery.outage(),
                        recovery.reason()));
    }
}
