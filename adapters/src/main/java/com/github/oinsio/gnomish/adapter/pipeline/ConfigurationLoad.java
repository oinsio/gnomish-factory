package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.baseref.BaseDefinition;
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;

/**
 * Everything one pass over a {@code .gnomish/} tree produces: the task tier's {@link LoadOutcome},
 * and the trusted tier's {@link BaseDefinition} (FR1, FR2 of add-base-ref-resolution, design D3).
 *
 * <p>Two values rather than one, because the two tiers are read from different refs and bound at
 * different times: the base definition is bound once at startup from the repository default branch
 * and picks the base, and only then does the task tier bind from the base's own law commit. Folding
 * the definition into {@code PipelineDefinition} would put a startup-scoped fact inside a per-task
 * value, and would also point the dependency-free {@code :domain} at {@code :baseref}, which the
 * module-layering gate forbids in both directions.
 *
 * <p>Both components come out of the same single-pass load, so the section's problems aggregate with
 * every other located error (UX1) instead of being reported by a second read.
 *
 * <p>Implements FR1, FR2 of add-base-ref-resolution.
 *
 * @param outcome the task tier: the validated definition, or every located problem
 * @param base the trusted tier's base policy; {@link BaseDefinition#none()} when the section is
 *     absent, and a best-effort value when the load failed — the caller acts on {@code outcome}
 *     first, so a definition built from a rejected tree is never consulted
 */
public record ConfigurationLoad(LoadOutcome outcome, BaseDefinition base) {}
