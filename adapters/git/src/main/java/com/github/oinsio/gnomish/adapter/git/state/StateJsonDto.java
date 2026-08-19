package com.github.oinsio.gnomish.adapter.git.state;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code state.json} v1 contract's top-level shape (design D3), written
 * only by the git {@code AttemptPersistence}: {@code version}, {@code
 * position}, {@code attemptsUsed}, {@code attempts[]}, and the cumulative
 * {@code totals} — mirrors the domain's {@link
 * com.github.oinsio.gnomish.domain.engine.TaskState} 1:1 so this DTO round-trips
 * fully back into the domain.
 *
 * <p>{@code attempts} covers only the current stage and resets on advancement;
 * earlier rounds remain reachable only in the file's git history (design D4,
 * FR14).
 *
 * <p>{@code egressCursor} is the one component with no domain counterpart: it is
 * environment bookkeeping the resuming instance hands back to its environment
 * (FR5 of fix-denial-report-attachment), not task state, so {@code
 * StateJsonMapper.fromDto} does not carry it into {@link
 * com.github.oinsio.gnomish.domain.engine.TaskState} — the resume path reads it
 * off this DTO directly. Additive under contract v1: absent in every document
 * written before the field existed, and {@code null} whenever the writing
 * environment had no denial source (host mode) or had not read one yet.
 *
 * <p>Implements FR3, FR4 of add-git-workflow; FR5 of
 * fix-denial-report-attachment.
 *
 * @param version the state-file contract version, {@code 1}
 * @param position where the task sits in its pipeline
 * @param attemptsUsed quality failures burned in the current stage
 * @param attempts every executed round of the current stage, in order;
 *     possibly empty
 * @param totals cumulative executor usage over the whole task, surviving stage
 *     advancement and resume
 * @param egressCursor the environment's denial read position at commit time, or
 *     {@code null} when there is none
 */
public record StateJsonDto(
        int version,
        StatePositionDto position,
        int attemptsUsed,
        List<StateAttemptDto> attempts,
        StateUsageDto totals,
        @Nullable StateEgressCursorDto egressCursor) {}
