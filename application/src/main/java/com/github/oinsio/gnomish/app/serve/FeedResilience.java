package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.take.FinishedDecline;

/**
 * {@link FeedCycle}'s outage-handling collaborators, grouped into their own parameter object so
 * the record's own constructor stays under the parameter-count limit (process-invariants.md): the
 * tracker-outage retry (NFR-R3 of add-factory-serve), the terminal-status sweep (design D4 of
 * enforce-finish-terminality), and the remote outage gate (FR14, NFR-R3 of
 * add-base-ref-resolution) that blocks a claim attempt while the clone's {@code origin} is
 * unreachable. Grouped rather than left as three sibling {@link FeedCycle} fields because all
 * three answer the same question — "how does this cycle behave under a dependency outage" — while
 * {@link FeedCycle}'s remaining fields answer "who and what this cycle claims for and against"; a
 * genuine responsibility split, not a cosmetic one.
 *
 * <p>Implements FR14, NFR-R3 of add-base-ref-resolution.
 *
 * @param outageRetry the tracker-outage retry every tracker call in the cycle runs through
 * @param finishedDecline the terminal-status sweep applied to each feed read
 * @param remoteOutageGate the remote outage gate the cycle probes and consults before every claim
 */
record FeedResilience(
        FeedOutageRetry outageRetry, FinishedDecline finishedDecline, RemoteOutageGate remoteOutageGate) {}
