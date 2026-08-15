package com.github.oinsio.gnomish.app.port.git;

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
 * @param attemptCommit the sha of the snapshot (attempt) commit to verify; never null
 * @param stage the stage the interrupted round belonged to; never null
 * @param round the interrupted round's number within that stage
 */
public record PendingVerification(String attemptCommit, String stage, int round) {}
