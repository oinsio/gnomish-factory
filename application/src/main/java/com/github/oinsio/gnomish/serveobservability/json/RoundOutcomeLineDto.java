package com.github.oinsio.gnomish.serveobservability.json;

/**
 * The JSON contract's {@code roundOutcome} ledger line (NFR-O2, UX3 of define-executor-contract):
 * one per executor round, naming the executor, the role and the status in the words of {@code
 * executor=<name>@<version> role=<role> status=<status>[/<class>]}.
 *
 * @param version the contract version; always {@code 1}
 * @param type the line-type discriminator; always {@code "roundOutcome"}
 * @param instance the writing process's identity
 * @param at ISO-8601 UTC instant the round finished
 * @param taskId the tracker's task id
 * @param stage the stage the round ran
 * @param attempt the round's 0-based number in the stage
 * @param executor {@code <name>@<version>}, or {@code <name>} for an executor with no describe
 * @param role the round's role token
 * @param status {@code <status>[/<class>]}
 */
public record RoundOutcomeLineDto(
        int version,
        String type,
        InstanceDto instance,
        String at,
        String taskId,
        String stage,
        int attempt,
        String executor,
        String role,
        String status) {}
