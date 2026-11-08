package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Instant;
import java.util.List;

/**
 * The poll loop of one {@link VerifyCheck.Executor} check, extracted from {@link
 * VerifyOrchestrator} so each class stays within the file-size cap. It polls the
 * injected {@link ExternalCheckClient} until the third party decides or the timeout
 * elapses, collapsing the {@link PollStatus} observations into one {@link VerdictAnswer}
 * (design D2, D8); the verdict is derived from it by {@link VerdictDerivation}, never
 * here (D5, D9 of migrate-verify-to-executor-contract).
 *
 * <p>The {@code deadline} is {@code clock.instant() + timeout} captured once at entry. A
 * decided poll maps straight through to the matching answer — a pass to an empty {@link
 * VerdictAnswer.Sarif}, a fail to a {@link VerdictAnswer.QualityFailure} with its
 * findings, a cannot-verify to a {@link VerdictAnswer.InfrastructureFailure}; {@link
 * PollStatus.Running} keeps waiting. On {@code Running} the timeout is checked
 * <em>before</em> sleeping: once the equipment has no time {@linkplain TimeEquipment#remaining
 * remaining} until the deadline
 * the loop resolves the check's declared {@link VerifyCheck.Executor#timeoutClass()}
 * — {@link VerifyCheck.TimeoutClass#QUALITY} (the default) returns a {@link
 * VerdictAnswer.QualityFailure} with a single timeout {@link Finding}, unchanged prior
 * behavior; {@link VerifyCheck.TimeoutClass#INFRASTRUCTURE} returns a {@link
 * VerdictAnswer.InfrastructureFailure} naming the elapsed timeout instead, so the stage escalates
 * without burning an attempt (FR9). Otherwise the loop sleeps the interval on the
 * equipment's sleeper and polls again. Checking before sleeping keeps the loop
 * deterministic (NFR-R3).
 *
 * <p>Package-private and reentrant: it holds only its immutable injected collaborators
 * and no mutable state, so one instance drives concurrent external checks safely — all
 * loop state is local to {@link #poll} (NFR-R1).
 *
 * <p>A decided {@link PollStatus.Pass} maps through with its platform run URL intact on the answer,
 * so the recorded check result carries the link a green external check is audited by
 * (NFR-O2 of add-sandbox-core).
 *
 * <p>Implements FR3, NFR-R3 of add-stage-engine; FR9 of
 * add-external-check-github-actions; NFR-O2 of add-sandbox-core; FR7 of
 * migrate-verify-to-executor-contract.
 */
final class ExternalPolling {

    private final ExternalCheckClient externalClient;
    private final AttemptDelivery attemptDelivery;
    private final TimeEquipment time;

    /**
     * Wires the poll loop's collaborators: the {@link ExternalCheckClient} polled once
     * per iteration, the {@link AttemptDelivery} precondition confirmed before the loop
     * starts (FR21 of add-sandbox-core), and the injected {@link TimeEquipment} whose clock the
     * deadline is measured against and whose sleeper is waited between polls (design D8). All
     * immutable (NFR-R1).
     *
     * @param externalClient the port polled once per iteration; never null
     * @param attemptDelivery the push-precondition seam confirmed before the loop; never null
     * @param time the injected time equipment: the poll deadline's clock and the sleep seam waited
     *     between polls (design D20 of supervise-daemon-loops-and-embed-dashboard); never null
     */
    ExternalPolling(ExternalCheckClient externalClient, AttemptDelivery attemptDelivery, TimeEquipment time) {
        this.externalClient = externalClient;
        this.attemptDelivery = attemptDelivery;
        this.time = time;
    }

    /**
     * Polls {@code check} until it decides or its timeout elapses, collapsing the poll
     * sequence into one {@link VerdictAnswer}. The deadline is {@code clock.instant() +
     * check.timeout()} captured once at entry; a {@link PollStatus.Running} that never
     * resolves times out once no time remains until the deadline,
     * classified per {@code check.timeoutClass()}: {@link VerifyCheck.TimeoutClass#QUALITY}
     * (default) into a {@link VerdictAnswer.QualityFailure}, {@link
     * VerifyCheck.TimeoutClass#INFRASTRUCTURE} into a {@link VerdictAnswer.InfrastructureFailure} (FR9).
     * The timeout is checked before sleeping so virtual time never overshoots (NFR-R3);
     * all loop state is local.
     *
     * <p>Implements FR3, NFR-R3 of add-stage-engine; FR9 of
     * add-external-check-github-actions.
     *
     * @param check the external check to poll, carrying its interval, timeout and id
     * @param workspace the opaque working copy the check relates to
     * @return the answer the poll sequence collapses to
     */
    VerdictAnswer poll(VerifyCheck.Executor check, Workspace workspace) {
        // FR21 of add-sandbox-core: external checks are triggered by the task-branch push, so
        // delivery of the attempt commit is a verified precondition of the loop — an
        // undeliverable commit resolves as CannotVerify (no attempt burned) instead of being
        // left to expire as a poll-timeout quality failure.
        if (attemptDelivery.ensureDelivered(workspace)
                instanceof AttemptDelivery.Outcome.Undeliverable(UntrustedText reason, UntrustedText details)) {
            return new VerdictAnswer.InfrastructureFailure(reason, details);
        }
        Instant deadline = time.clock().instant().plus(check.timeout());
        while (true) {
            switch (externalClient.poll(check, workspace)) {
                case PollStatus.Pass pass -> {
                    return new VerdictAnswer.Sarif(List.of(), pass.runUrl());
                }
                case PollStatus.Fail f -> {
                    return new VerdictAnswer.QualityFailure(f.findings());
                }
                case PollStatus.CannotVerify cv -> {
                    return new VerdictAnswer.InfrastructureFailure(cv.reason(), cv.details());
                }
                case PollStatus.Running ignored -> {
                    if (time.remaining(deadline).isZero()) {
                        return timeoutAnswer(check);
                    }
                    time.sleeper().sleep(check.interval());
                }
            }
        }
    }

    /**
     * Classifies a timed-out external check's answer per its declared {@link
     * VerifyCheck.Executor#timeoutClass()} (design D7): {@link
     * VerifyCheck.TimeoutClass#QUALITY} (default) into a {@link VerdictAnswer.QualityFailure}
     * carrying a single timeout {@link Finding} naming the check id and timeout,
     * burning the attempt (NFR-O1, unchanged prior behavior); {@link
     * VerifyCheck.TimeoutClass#INFRASTRUCTURE} into a {@link VerdictAnswer.InfrastructureFailure}
     * naming the same elapsed timeout, escalating without burning an attempt.
     *
     * <p>Implements FR9 of add-external-check-github-actions.
     */
    private static VerdictAnswer timeoutAnswer(VerifyCheck.Executor check) {
        var message = "external check '" + check.checkId() + "' did not complete within " + check.timeout();
        return switch (check.timeoutClass()) {
            case QUALITY -> new VerdictAnswer.QualityFailure(List.of(new Finding(message, null, null)));
            // MANIFEST, not FACTORY: the sentence interpolates the check id raw, and a capture
            // quoted raw keeps the capture's family (design D1 of type-untrusted-text). The detail
            // is the empty carrier: a timeout is the absence of an answer, so nothing was captured,
            // and every renderer prints reason and details in turn — handing the one sentence to
            // both fields would show the operator the same line twice (design D3, "the empty
            // details of a verdict that captured nothing" is FACTORY).
            case INFRASTRUCTURE ->
                new VerdictAnswer.InfrastructureFailure(UntrustedText.manifest(message), UntrustedText.factory(""));
        };
    }
}
