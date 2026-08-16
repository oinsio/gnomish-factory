package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.time.Instant;
import java.util.List;

/**
 * The poll loop of one {@link VerifyCheck.External} check, extracted from {@link
 * VerifyOrchestrator} so each class stays within the file-size cap. It polls the
 * injected {@link ExternalCheckClient} until the third party decides or the timeout
 * elapses, collapsing the {@link PollStatus} observations into one {@link Verdict}
 * (design D2, D8).
 *
 * <p>The {@code deadline} is {@code clock.now() + timeout} captured once at entry. A
 * decided poll maps straight through to the matching {@link Verdict}; {@link
 * PollStatus.Running} keeps waiting. On {@code Running} the timeout is checked
 * <em>before</em> sleeping: once {@code clock.now()} is no longer before the deadline
 * the loop resolves the check's declared {@link VerifyCheck.External#timeoutClass()}
 * — {@link VerifyCheck.TimeoutClass#QUALITY} (the default) returns a quality {@link
 * Verdict.Fail} with a single timeout {@link Finding}, unchanged prior behavior;
 * {@link VerifyCheck.TimeoutClass#INFRASTRUCTURE} returns a {@link
 * Verdict.CannotVerify} naming the elapsed timeout instead, so the stage escalates
 * without burning an attempt (FR9). Otherwise the loop sleeps the interval on the
 * injected {@link Sleeper} and polls again. Checking before sleeping keeps the loop
 * deterministic (NFR-R3).
 *
 * <p>Package-private and reentrant: it holds only its immutable injected collaborators
 * and no mutable state, so one instance drives concurrent external checks safely — all
 * loop state is local to {@link #poll} (NFR-R1).
 *
 * <p>A decided {@link PollStatus.Pass} maps through with its platform run URL intact,
 * so the recorded check result carries the link a green external check is audited by
 * (NFR-O2 of add-sandbox-core).
 *
 * <p>Implements FR3, NFR-R3 of add-stage-engine; FR9 of
 * add-external-check-github-actions; NFR-O2 of add-sandbox-core.
 */
final class ExternalPolling {

    private final ExternalCheckClient externalClient;
    private final AttemptDelivery attemptDelivery;
    private final Clock clock;
    private final Sleeper sleeper;

    /**
     * Wires the poll loop's collaborators: the {@link ExternalCheckClient} polled once
     * per iteration, the {@link AttemptDelivery} precondition confirmed before the loop
     * starts (FR21 of add-sandbox-core), the injected {@link Clock} the deadline is
     * measured against, and the {@link Sleeper} waited between polls (design D8). All
     * immutable (NFR-R1).
     *
     * @param externalClient the port polled once per iteration; never null
     * @param attemptDelivery the push-precondition seam confirmed before the loop; never null
     * @param clock the injected time source timing the poll deadline; never null
     * @param sleeper the injected sleep seam waited between polls; never null
     */
    ExternalPolling(ExternalCheckClient externalClient, AttemptDelivery attemptDelivery, Clock clock, Sleeper sleeper) {
        this.externalClient = externalClient;
        this.attemptDelivery = attemptDelivery;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Polls {@code check} until it decides or its timeout elapses, collapsing the poll
     * sequence into one {@link Verdict}. The deadline is {@code clock.now() +
     * check.timeout()} captured once at entry; a {@link PollStatus.Running} that never
     * resolves times out once {@code clock.now()} is no longer before the deadline,
     * classified per {@code check.timeoutClass()}: {@link VerifyCheck.TimeoutClass#QUALITY}
     * (default) into a quality {@link Verdict.Fail}, {@link
     * VerifyCheck.TimeoutClass#INFRASTRUCTURE} into a {@link Verdict.CannotVerify} (FR9).
     * The timeout is checked before sleeping so virtual time never overshoots (NFR-R3);
     * all loop state is local.
     *
     * <p>Implements FR3, NFR-R3 of add-stage-engine; FR9 of
     * add-external-check-github-actions.
     *
     * @param check the external check to poll, carrying its interval, timeout and id
     * @param workspace the opaque working copy the check relates to
     * @return the verdict the poll sequence collapses to
     */
    Verdict poll(VerifyCheck.External check, Workspace workspace) {
        // FR21 of add-sandbox-core: external checks are triggered by the task-branch push, so
        // delivery of the attempt commit is a verified precondition of the loop — an
        // undeliverable commit resolves as CannotVerify (no attempt burned) instead of being
        // left to expire as a poll-timeout quality failure.
        if (attemptDelivery.ensureDelivered(workspace) instanceof AttemptDelivery.Outcome.Undeliverable undeliverable) {
            return new Verdict.CannotVerify(undeliverable.reason(), undeliverable.details());
        }
        Instant deadline = clock.now().plus(check.timeout());
        while (true) {
            switch (externalClient.poll(check, workspace)) {
                case PollStatus.Pass pass -> {
                    return new Verdict.Pass(pass.runUrl());
                }
                case PollStatus.Fail f -> {
                    return new Verdict.Fail(f.findings());
                }
                case PollStatus.CannotVerify cv -> {
                    return new Verdict.CannotVerify(cv.reason(), cv.details());
                }
                case PollStatus.Running ignored -> {
                    if (!clock.now().isBefore(deadline)) {
                        return timeoutVerdict(check);
                    }
                    sleeper.sleep(check.interval());
                }
            }
        }
    }

    /**
     * Classifies a timed-out external check's verdict per its declared {@link
     * VerifyCheck.External#timeoutClass()} (design D7): {@link
     * VerifyCheck.TimeoutClass#QUALITY} (default) into a quality {@link Verdict.Fail}
     * carrying a single timeout {@link Finding} naming the check id and timeout,
     * burning the attempt (NFR-O1, unchanged prior behavior); {@link
     * VerifyCheck.TimeoutClass#INFRASTRUCTURE} into a {@link Verdict.CannotVerify}
     * naming the same elapsed timeout, escalating without burning an attempt.
     *
     * <p>Implements FR9 of add-external-check-github-actions.
     */
    private static Verdict timeoutVerdict(VerifyCheck.External check) {
        var message = "external check '" + check.checkId() + "' did not complete within " + check.timeout();
        return switch (check.timeoutClass()) {
            case QUALITY -> new Verdict.Fail(List.of(new Finding(message, null, null)));
            case INFRASTRUCTURE -> new Verdict.CannotVerify(message, message);
        };
    }
}
