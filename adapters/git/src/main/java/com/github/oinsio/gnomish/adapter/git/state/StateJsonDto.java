package com.github.oinsio.gnomish.adapter.git.state;

import java.util.List;

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
 * <p>Implements FR3, FR4 of add-git-workflow.
 *
 * @param version the state-file contract version, {@code 1}
 * @param position where the task sits in its pipeline
 * @param attemptsUsed quality failures burned in the current stage
 * @param attempts every executed round of the current stage, in order;
 *     possibly empty
 * @param totals cumulative executor usage over the whole task, surviving stage
 *     advancement and resume
 */
public record StateJsonDto(
        int version,
        StatePositionDto position,
        int attemptsUsed,
        List<StateAttemptDto> attempts,
        StateUsageDto totals) {}
