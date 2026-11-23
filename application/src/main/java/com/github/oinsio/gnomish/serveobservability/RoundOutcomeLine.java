package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A ledger {@code roundOutcome} line: one per executor round a serve slot ran, recorded or not
 * (NFR-O2, UX3 of define-executor-contract) — {@code executor=<name>@<version> role=<role>
 * status=<status>[/<class>]}, beside the round's {@code taskId}, {@code stage} and {@code attempt}.
 * Before this line the ledger wrote nothing per round; usage stays on {@link TaskOutcomeLine} only,
 * so a day view summing {@code taskOutcome} snapshots is unchanged by it.
 *
 * <p>An executor check writes the same line with the role {@code verdict} (NFR-O1 of
 * migrate-verify-to-executor-contract): one per check, naming the status its rounds ended on, the
 * check's index in the stage's {@code verify} list, and — when this instance resumed the check's
 * wait from the round's pending record — the prefix of the key it took from there, the ledger's
 * {@code resumed key} (UX3). A stage round has no check index; a {@code transform} round that went
 * {@code pending} on this instance names the prefix of the key its {@code start} returned, the
 * ledger's {@code pending key}, and one this instance resumed from the round's pending record the
 * prefix of the key it took from there, the {@code resumed key} (UX3 of extract-agent-round-runner).
 *
 * <p>The words are the ones {@code RoundOutcomeWords} owns; the executor's version and a resumed key
 * are their own text and stay carriers until the ledger's machine exit writes them. Inert value
 * data compared by content.
 *
 * <p>Implements NFR-O2, UX3 of define-executor-contract; NFR-O1, UX3 of
 * migrate-verify-to-executor-contract; UX3 of extract-agent-round-runner.
 *
 * @param instance the writing process's identity; never null
 * @param at when the round finished; never null
 * @param taskId the tracker's task id; never blank
 * @param stage the stage the round ran; never blank
 * @param attempt the round's 0-based number in the stage; never negative
 * @param executorName the executor's name, or the stage's executor type word; never blank
 * @param executorVersion the executor's describe version; null for an executor with no describe
 * @param role the round's role token ({@code transform} for a stage round); never blank
 * @param status the round's {@code <status>[/<class>]} token; never blank
 * @param check the check's index in the stage's {@code verify} list; null for a stage round
 * @param resumedKey the prefix of the key a resume took from the pending record; null otherwise
 * @param pendingKey the prefix of the key a stage round's {@code start} went pending with on this
 *     instance; null otherwise
 */
public record RoundOutcomeLine(
        InstanceInfo instance,
        Instant at,
        String taskId,
        String stage,
        int attempt,
        String executorName,
        @Nullable UntrustedText executorVersion,
        String role,
        String status,
        @Nullable Integer check,
        @Nullable UntrustedText resumedKey,
        @Nullable UntrustedText pendingKey)
        implements LedgerLine {}
