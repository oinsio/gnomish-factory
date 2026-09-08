package com.github.oinsio.gnomish.app.port.pipeline;

import com.github.oinsio.gnomish.baseref.BaseDefinition;
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import com.github.oinsio.gnomish.gitobjects.ObjectId;

/**
 * Both configuration tiers as one startup read of the law a binding names (FR2, FR13, design D14,
 * D15 of add-base-ref-resolution): the task tier's {@link LoadOutcome}, the trusted tier's {@link
 * BaseDefinition}, and the law commit they were read from.
 *
 * <p>This is the startup shape only. A claim reads {@link BoundTaskTier} — a value with no base
 * definition in it — so the trusted tier cannot be re-read per task by construction (D15).
 *
 * <p>Implements FR2, FR13 of add-base-ref-resolution.
 *
 * @param outcome the task tier: the validated definition, or every located problem
 * @param base the trusted tier's base policy; a best-effort value when {@code outcome} is invalid,
 *     which the caller never consults because it acts on {@code outcome} first
 * @param lawCommit the commit the law was read from — the refreshed default-branch tip at startup
 */
public record BoundConfiguration(LoadOutcome outcome, BaseDefinition base, ObjectId lawCommit) {}
