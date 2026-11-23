package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.app.port.agent.AnswerFileRead;
import com.github.oinsio.gnomish.app.port.agent.AnswerFileReading;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import java.util.List;

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
 * <p>A round that wrote an answer file carries it in the snapshot's tree, at the path its round
 * token names; the detector reads it from there — a read of the durable medium, never of a working
 * copy — through the one {@link AnswerFileRead} the live round reads its file through, so the
 * resuming executor gives exactly the outcome the live round would have given — the question
 * re-raised, or the refusal of a file the live round would have refused — without re-running the
 * round (FR15, NFR-R4 of make-checkpoint-gate-durable, design D10; FR21, NFR-R5 of
 * extract-agent-round-runner, design D15: recovery parses through the live path's read).
 *
 * <p>The round's token travels on the value, typed, as the snapshot subject recorded it: the
 * resuming executor {@linkplain CurrentRound#restore restores} the round from it, so the pickup's
 * state commit is judged against the identity the round opened under and never against a re-read
 * tip (FR13, FR15 of make-checkpoint-gate-durable, design D10 as amended 2026-10-07).
 *
 * <p>Two commits, as the round's {@link ClosedRound} holds them (design D21 of
 * migrate-verify-to-executor-contract): the snapshot the checks judge, and the round's last
 * registered commit — the snapshot itself, or the last pending commit landed on it, which carries the
 * round's pending entries. The restored round's close sits on {@code registered}. Beside them, the
 * round's registered chain the resume's one walk passed ({@code PendingChain}, design D14 as amended
 * 2026-11-13): {@code registered} first, {@code attemptCommit} last — so the run's round cell holds
 * the chain from the record and nothing walks it again (crash-consistency item 14).
 *
 * <p>Implements FR21 of add-sandbox-core; FR3, FR20 of migrate-verify-to-executor-contract; FR21 of
 * extract-agent-round-runner.
 *
 * @param attemptCommit the sha of the snapshot (attempt) commit to verify; never null
 * @param stage the stage the interrupted round belonged to; never null
 * @param round the interrupted round's number within that stage
 * @param token the identity the interrupted round opened under, from its snapshot subject; never
 *     null
 * @param answer the reading of the answer file the snapshot's tree holds under the round's token:
 *     absent when the round wrote none; never null
 * @param registered the round's last registered commit: {@code attemptCommit} when the register
 *     names the snapshot, else the registered pending commit above it; never null
 * @param chain the round's registered commits as the walk passed them, {@code registered} first and
 *     {@code attemptCommit} last; copied; never empty
 */
public record PendingVerification(
        String attemptCommit,
        String stage,
        int round,
        RoundToken token,
        AnswerFileReading answer,
        String registered,
        List<String> chain) {

    /** Copies the chain, and refuses one that does not run from {@code registered} to the snapshot. */
    public PendingVerification {
        chain = List.copyOf(chain);
        requireSpans(chain, registered, attemptCommit);
    }

    private static void requireSpans(List<String> chain, String registered, String attemptCommit) {
        if (chain.isEmpty()
                || !chain.getFirst().equals(registered)
                || !chain.getLast().equals(attemptCommit)) {
            throw new IllegalArgumentException(
                    "PendingVerification.chain must run from the registered commit down to the snapshot: " + chain);
        }
    }
}
