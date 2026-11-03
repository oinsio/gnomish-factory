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
 * <p>The words are the ones {@code RoundOutcomeWords} owns; the executor's version is its own text
 * and stays a carrier until the ledger's machine exit writes it. Inert value data compared by content.
 *
 * <p>Implements NFR-O2, UX3 of define-executor-contract.
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
        String status)
        implements LedgerLine {}
