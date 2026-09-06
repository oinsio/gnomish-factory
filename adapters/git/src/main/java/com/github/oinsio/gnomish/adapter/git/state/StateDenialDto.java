package com.github.oinsio.gnomish.adapter.git.state;

import org.jspecify.annotations.Nullable;

/**
 * One recorded egress denial as both task-branch envelopes carry it — an attempt's {@code
 * denials} in {@code state.json} and a {@code cannotExecute} escalation's in {@code task.json}.
 * The finding fields mirror {@link StateFindingDto} exactly, because a denial reads like any
 * other finding to a reviewer; the extra {@code identity} is what a check finding has no
 * equivalent of (FR7 of fix-denial-attribution-durability).
 *
 * <p>A shape of its own rather than three more components on {@link StateFindingDto}: that DTO is
 * also the wire form of a failed check's findings, whose bytes must not gain fields they can
 * never carry. Splitting the two is what lets identity be additive for denials alone.
 *
 * <p>Implements FR4 of fix-denial-report-attachment; FR7 of fix-denial-attribution-durability.
 *
 * @param message what was denied
 * @param location an optional locator, or {@code null} if none
 * @param details optional extra detail, or {@code null} if none
 * @param identity the source-assigned identity, or {@code null} for a denial no source stamped —
 *     a loss marker, or an entry written before the field existed ("unknown, keep")
 */
public record StateDenialDto(
        String message,
        @Nullable String location,
        @Nullable String details,
        @Nullable DenialIdentityDto identity) {}
