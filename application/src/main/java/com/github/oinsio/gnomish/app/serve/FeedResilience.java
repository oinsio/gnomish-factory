package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.take.FinishedDecline;

/**
 * {@link FeedCycle}'s outage-handling collaborators, grouped into their own parameter object so
 * the record's own constructor stays under the parameter-count limit (process-invariants.md): the
 * tracker-outage retry (NFR-R3 of add-factory-serve), the terminal-status sweep (design D4 of
 * enforce-finish-terminality), and the daemon's conditions (FR14, NFR-R3 of
 * add-base-ref-resolution; D19 of add-sandbox-base-image) that block a claim attempt while a
 * precondition the daemon needs, the clone's {@code origin} among them, is lost. Grouped rather than left as three sibling {@link FeedCycle} fields because all
 * three answer the same question — "how does this cycle behave under a dependency outage" — while
 * {@link FeedCycle}'s remaining fields answer "who and what this cycle claims for and against"; a
 * genuine responsibility split, not a cosmetic one.
 *
 * <p>Implements FR14, NFR-R3 of add-base-ref-resolution.
 *
 * @param outageRetry the tracker-outage retry every tracker call in the cycle runs through
 * @param finishedDecline the terminal-status sweep applied to each feed read
 * @param conditions the daemon's conditions the cycle probes and consults before every claim
 */
record FeedResilience(FeedOutageRetry outageRetry, FinishedDecline finishedDecline, DaemonConditions conditions) {}
