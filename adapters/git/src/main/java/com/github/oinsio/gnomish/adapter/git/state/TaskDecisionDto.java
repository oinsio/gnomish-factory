package com.github.oinsio.gnomish.adapter.git.state;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code task.json} contract's {@code decisions[]} entry: {@code body},
 * {@code author}, {@code stage}, {@code at} — mirrors the domain's {@link
 * com.github.oinsio.gnomish.domain.engine.Decision} field-for-field (FR3) — and, for a decision
 * that answers a decision request, the {@code question} and {@code options} it answers (FR9 of
 * extract-agent-round-runner). Both are omitted when absent, so an entry written before they existed
 * is written back unchanged.
 *
 * <p>Implements FR3, FR4 of add-git-workflow; FR9, NFR-R2 of extract-agent-round-runner.
 *
 * @param body the free-text decision message, carried verbatim
 * @param author who made the decision, or {@code null} if unattributed
 * @param stage the stage the decision pertains to, or {@code null} if unscoped
 * @param at ISO-8601 UTC instant the decision was recorded, or {@code null} if
 *     unknown
 * @param question the question of the decision request this decision answers, or {@code null}
 *     when it answers none
 * @param options that request's options, or {@code null} when it answers none
 */
public record TaskDecisionDto(
        String body,
        @Nullable String author,
        @Nullable String stage,
        @Nullable String at,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String question,
        @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable List<String> options) {}
