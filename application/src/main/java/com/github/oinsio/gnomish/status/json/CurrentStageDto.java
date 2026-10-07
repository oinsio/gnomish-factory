package com.github.oinsio.gnomish.status.json;

import java.util.List;

/**
 * The JSON contract's {@code currentStage} section: {@code attemptsUsed} and {@code
 * attempts} (spec.md). The whole section is {@code null} at
 * {@code pipelineEnd}, where the attempt history has been reset by advancement.
 *
 * <p>Implements FR11, M3 of add-manual-run; FR6 of make-run-headless.
 *
 * @param attemptsUsed quality failures burned in the current stage
 * @param attempts every executed round of the current stage, in order
 */
public record CurrentStageDto(int attemptsUsed, List<AttemptDto> attempts) {}
