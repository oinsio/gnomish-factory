package com.github.oinsio.gnomish.domain.engine;

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
 * the loop returns a quality {@link Verdict.Fail} with a single timeout {@link
 * Finding}; otherwise it sleeps the interval on the injected {@link Sleeper} and polls
 * again. Checking before sleeping keeps the loop deterministic (NFR-R3).
 *
 * <p>Package-private and reentrant: it holds only its immutable injected collaborators
 * and no mutable state, so one instance drives concurrent external checks safely — all
 * loop state is local to {@link #poll} (NFR-R1).
 *
 * <p>Implements FR3, NFR-R3 of add-stage-engine.
 */
final class ExternalPolling {

    private final ExternalCheckClient externalClient;
    private final Clock clock;
    private final Sleeper sleeper;

    /**
     * Wires the poll loop's collaborators: the {@link ExternalCheckClient} polled once
     * per iteration, the injected {@link Clock} the deadline is measured against, and
     * the {@link Sleeper} waited between polls (design D8). All immutable (NFR-R1).
     *
     * @param externalClient the port polled once per iteration; never null
     * @param clock the injected time source timing the poll deadline; never null
     * @param sleeper the injected sleep seam waited between polls; never null
     */
    ExternalPolling(ExternalCheckClient externalClient, Clock clock, Sleeper sleeper) {
        this.externalClient = externalClient;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Polls {@code check} until it decides or its timeout elapses, collapsing the poll
     * sequence into one {@link Verdict}. The deadline is {@code clock.now() +
     * check.timeout()} captured once at entry; a {@link PollStatus.Running} that never
     * resolves times out into a quality {@link Verdict.Fail} once {@code clock.now()} is
     * no longer before the deadline. The timeout is checked before sleeping so virtual
     * time never overshoots (NFR-R3); all loop state is local.
     *
     * <p>Implements FR3, NFR-R3 of add-stage-engine.
     *
     * @param check the external check to poll, carrying its interval, timeout and id
     * @param workspace the opaque working copy the check relates to
     * @return the verdict the poll sequence collapses to
     */
    Verdict poll(VerifyCheck.External check, Workspace workspace) {
        Instant deadline = clock.now().plus(check.timeout());
        while (true) {
            switch (externalClient.poll(check, workspace)) {
                case PollStatus.Pass ignored -> {
                    return new Verdict.Pass();
                }
                case PollStatus.Fail f -> {
                    return new Verdict.Fail(f.findings());
                }
                case PollStatus.CannotVerify cv -> {
                    return new Verdict.CannotVerify(cv.reason(), cv.details());
                }
                case PollStatus.Running ignored -> {
                    if (!clock.now().isBefore(deadline)) {
                        return timeoutFailure(check);
                    }
                    sleeper.sleep(check.interval());
                }
            }
        }
    }

    /**
     * Builds the quality {@link Verdict.Fail} for a timed-out external check — a poll
     * timeout is a quality failure that burns the attempt; its single {@link Finding}
     * names the check id and timeout (NFR-O1).
     */
    private static Verdict timeoutFailure(VerifyCheck.External check) {
        var message = "external check '" + check.checkId() + "' did not complete within " + check.timeout();
        return new Verdict.Fail(List.of(new Finding(message, null, null)));
    }
}
