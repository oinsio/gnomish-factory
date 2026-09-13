package com.github.oinsio.gnomish.adapter.pipeline;

import org.jspecify.annotations.Nullable;

/**
 * The {@code task-branch} section of {@code config.yaml} (D16 of add-base-ref-resolution): settings
 * of the task branch itself. It holds one subsection in this version, {@code base}.
 *
 * <p>The root key names an area the way {@code tracker:} and {@code autonomy:} do, rather than a
 * single setting: a bare {@code base:} at the root of an infrastructure YAML reads as a base image,
 * a base URL or a merge base, while {@code task-branch.base} reads as the base of the task branch.
 * Later settings of the same branch — a name-prefix override is the named candidate — land here
 * instead of taking a new root key each.
 *
 * <p>Implements FR1, UX1 of add-base-ref-resolution.
 *
 * @param base the {@code base} subsection, or {@code null} when it is absent — which is valid, and
 *     means no allowed base and no configured default
 */
public record TaskBranchDto(@Nullable BaseDto base) {}
