package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.app.port.agent.DecisionRequestRead;
import com.github.oinsio.gnomish.app.port.agent.RoundDecisionRequest;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import java.util.Optional;

/**
 * A round whose attempt was committed but whose verdict never was: the factory died between the
 * snapshot commit and the state commit, i.e. <em>during verification</em> (FR21, design D15 of
 * add-sandbox-core). The resuming instance re-runs verification against exactly {@code
 * attemptCommit} and leaves the attempt counter unchanged — no attempt is burned for a verdict that
 * was never recorded.
 *
 * <p>Was {@code adapter.git.SnapshotTipCheck.InterruptedVerification}; relocated to the
 * port package by task 4.4 of split-into-modules (D12(a)) because it is a plain value type appearing in
 * application-owned signatures — the same treatment task 4.3 gave {@code DivergenceOutcome}.
 * Detecting one is still an adapter's job: it is read off the task branch tip as a bare-object
 * query.
 *
 * <p>A round that asked for a decision carries its request in the snapshot's tree, at the path its
 * round token names; the detector reads it from there — a read of the durable medium, never of a
 * working copy — through the same tolerant {@link DecisionRequestRead} a live round uses, so the
 * resuming executor re-raises exactly the question the live round would have raised, without
 * re-running the round (FR15, NFR-R4 of make-checkpoint-gate-durable, design D10; FR6 of
 * define-executor-contract: recovery parses through the live path's read).
 *
 * <p>The round's token travels on the value, typed, as the snapshot subject recorded it: the
 * resuming executor {@linkplain CurrentRound#restore restores} the round from it, so the pickup's
 * state commit is judged against the identity the round opened under and never against a re-read
 * tip (FR13, FR15 of make-checkpoint-gate-durable, design D10 as amended 2026-10-07).
 *
 * @param attemptCommit the sha of the snapshot (attempt) commit to verify; never null
 * @param stage the stage the interrupted round belonged to; never null
 * @param round the interrupted round's number within that stage
 * @param token the identity the interrupted round opened under, from its snapshot subject; never
 *     null
 * @param request the decision request the snapshot's tree holds under the round's token, read
 *     tolerantly, or empty when the round asked nothing; never null
 */
public record PendingVerification(
        String attemptCommit, String stage, int round, RoundToken token, Optional<RoundDecisionRequest> request) {}
