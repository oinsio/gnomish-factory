package com.github.oinsio.gnomish.adapter.pipeline;

import org.jspecify.annotations.Nullable;

/**
 * The {@code autonomy} block shared by {@code config.yaml} (the pipeline-wide
 * default) and {@code stage.yaml} (the per-stage override). Only the attempt
 * limit is modeled — token budgets are out of scope (NG8).
 *
 * <p>A wire-format DTO (D2): {@code attemptLimit} is boxed so a missing key
 * deserializes to {@code null} rather than {@code 0}, letting the mapper (task
 * 5.3) distinguish "declared 0" from "not declared" and the validators report an
 * out-of-range value (FR7). Value-range validation is not the DTO's concern.
 *
 * <p>Implements FR7 (DTO shape), D2 of load-pipeline-config.
 *
 * @param attemptLimit the declared attempt limit, or {@code null} when the block
 *     omits it
 */
public record AutonomyDto(@Nullable Integer attemptLimit) {}
