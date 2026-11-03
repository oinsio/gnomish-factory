package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.domain.engine.AttemptRecord;

/**
 * One reconstructed round of {@code gnomish usage} (FR14, NFR-C1 of add-git-workflow): the full
 * {@link AttemptRecord} that appeared for it, carried verbatim
 * rather than re-projected — round number, result and every participant entry of its usage are
 * reachable from {@code attempt}, so the text-mode
 * summary (task 5.6) and the {@code --json} full-granularity rendering can both be built from the
 * same row without a second git-history walk.
 *
 * <p>The round is carried as the domain {@link AttemptRecord}, not as the state-file DTO it was
 * parsed from: {@code state.json}'s shape is an adapter contract, and a port-level row typed in it
 * would bind {@code application} to the git adapter's wire format (FR12b, design D12 of
 * split-into-modules). The git adapter maps DTO → {@code AttemptRecord} on the way out, the same
 * mapping the resume path already performs.
 *
 * <p>Produced by the git-side usage-history walk, one row per detected round in chronological
 * (oldest→newest) order — see that walker's javadoc for exactly when a commit yields a row.
 *
 * <p>The row carries no stage of its own: the stage, like the task and the round token, is the
 * record's identity, written by the round's writer and read from it ({@code attempt().stage()}),
 * so no producer can bill a round to a stage its record does not name (design D13, FR14, FR19 of
 * define-executor-contract).
 *
 * <p>Implements FR14, NFR-C1 of add-git-workflow; FR19 of define-executor-contract.
 *
 * @param attempt the round's full recorded detail as a domain attempt record
 */
public record UsageRow(AttemptRecord attempt) {}
