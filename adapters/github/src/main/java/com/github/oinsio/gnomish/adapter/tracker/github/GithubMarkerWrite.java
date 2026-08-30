package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The full content of one marker write under an explicitly named scope and author — the parameter
 * object of {@link GithubMarkerWriter#write(GithubTaskId, GithubMarkerWrite)}, per the
 * more-than-7-parameters rule of {@code .claude/rules/process-invariants.md}: the flat signature
 * held four adjacent {@code String} parameters, a transposition hazard named components remove.
 *
 * <p>Implements FR11 of harden-task-branch-contract.
 *
 * @param kind the marker kind
 * @param scope the occurrence this write belongs to, appended to the kind in the content identity
 * @param humanText the human-readable text rendered below the structural line
 * @param reason the category riding the marker's reason field — a park reason's or recovery
 *     category's wire value — or {@code null} for the kinds that carry none
 * @param epoch the tenure to stamp, or {@code null} to stamp none
 * @param author the instance to record as the marker's author
 * @param at the marker's own timestamp — the recorded fact's time where the caller has one (an
 *     {@code AbortRecord}'s {@code at}, which the abort-facts fold reads back), not the moment the
 *     write happens to be re-driven
 */
public record GithubMarkerWrite(
        GithubMarkerKind kind,
        String scope,
        String humanText,
        @Nullable String reason,
        @Nullable ClaimEpoch epoch,
        String author,
        Instant at) {}
