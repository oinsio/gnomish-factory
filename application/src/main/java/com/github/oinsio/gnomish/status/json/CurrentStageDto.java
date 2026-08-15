package com.github.oinsio.gnomish.status.json;

import java.util.List;

/**
 * The JSON contract's {@code currentStage} section: {@code attemptsUsed}, {@code
 * attemptLimit}, {@code attempts} (spec.md). The whole section is {@code null} at
 * {@code pipelineEnd}, where the attempt history has been reset by advancement.
 *
 * <p>Implements FR11, M3 of add-manual-run.
 *
 * @param attemptsUsed quality failures burned in the current stage
 * @param attemptLimit the resolved attempt limit of the current stage
 * @param attempts every executed round of the current stage, in order
 */
public record CurrentStageDto(int attemptsUsed, int attemptLimit, List<AttemptDto> attempts) {}
