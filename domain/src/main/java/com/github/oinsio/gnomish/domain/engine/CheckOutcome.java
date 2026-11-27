package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import org.jspecify.annotations.Nullable;

/**
 * What an executor check's rounds came to, for the operator's planes (NFR-O1, UX3 of
 * migrate-verify-to-executor-contract): the executor the check's rounds ran with its version, as
 * {@link DescribedExecutors#executorOf(com.github.oinsio.gnomish.domain.pipeline.VerifyCheck.Executor)}
 * names it (NFR-O1 of extract-agent-round-runner), the status its rounds ended
 * on, and — for a check whose wait an instance resumed from the round's pending record — the prefix
 * of the key it took from that record. The ledger's {@code roundOutcome} line of a check reads it,
 * carried on {@link EngineEvent.CheckFinished}.
 *
 * <p>The status is the last round's when the executor ended the check; when the engine ended it —
 * the wait reached its timeout, the attempt commit was not delivered, the check's adapter threw — it
 * is the failure the engine resolved the check to ({@code failed/infrastructure}, or the declared
 * class of a timed-out running wait), so the ledger's status and the verdict's class agree. Never
 * {@code pending}: a check that has finished has no pending round.
 *
 * <p>The resumed key is the key the resuming instance read from the registered commit — the latest
 * rotation persisted before the pickup, if any — never a key a poll returned after the resume; those
 * are in the per-poll log lines. Inert value data.
 *
 * <p>Implements NFR-O1, UX3 of migrate-verify-to-executor-contract; NFR-O1 of
 * extract-agent-round-runner.
 *
 * @param executor the registry entry the check's rounds ran and its version; never null
 * @param status the status the check's rounds ended on; never null
 * @param resumedKey the prefix of the key a resume took from the pending record ({@link
 *     PendingKeyPrefix}), or null for a check this instance started
 */
public record CheckOutcome(
        RoundExecutor executor,
        ExecutionResult.Status status,
        @Nullable UntrustedText resumedKey) {}
