package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the requests of one subject came to (design D11 of extract-agent-round-runner) — a round that
 * never went {@code pending} is its one request; a pending wait of {@link PendingPoll}
 * is its {@code start} and polls: the
 * result that ended it — the terminal answer of a poll, or the failure the engine resolved the wait to
 * at its deadline, which no executor reported and so carries no usage — the prefixes of the key it
 * went pending with on this run and of the key a resume took from the pending record, and the usage
 * of every request it made, in order, which a {@code transform} round records under its one identity
 * (NFR-O2 of extract-agent-round-runner).
 *
 * @param result the result the wait ended on, never {@code pending}; never null
 * @param pendingKey the prefix of the key this run's {@code start} returned, or null on a resume
 * @param resumedKey the prefix of the key a resume took from the pending record, or null
 * @param usages the usage of each request of the wait — the {@code start}'s first when this run made
 *     it, then each {@code poll}'s; copied, unmodifiable
 */
record PendingRun(
        ExecutionResult result,
        @Nullable UntrustedText pendingKey,
        @Nullable UntrustedText resumedKey,
        List<ExecutorUsage> usages) {

    PendingRun {
        usages = List.copyOf(usages);
    }
}
