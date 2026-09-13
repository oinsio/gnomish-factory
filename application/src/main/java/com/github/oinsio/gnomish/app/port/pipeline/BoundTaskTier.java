package com.github.oinsio.gnomish.app.port.pipeline;

import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome;
import com.github.oinsio.gnomish.gitobjects.ObjectId;

/**
 * The task tier of the law a binding names, read for one task (FR13, design D14 of
 * add-base-ref-resolution): the pipeline definition that governs the task, or every located
 * problem that keeps it from loading, beside the law commit it was read from.
 *
 * <p>Deliberately without the trusted tier: the base policy a claim ran under was bound once at
 * startup ({@link BoundConfiguration}), and a value that cannot carry a second copy is how "never
 * re-read per claim" (D15) is kept rather than merely promised.
 *
 * <p>Implements FR13 of add-base-ref-resolution.
 *
 * @param outcome the task tier: the validated definition, or every located problem
 * @param lawCommit the commit the law was read from — what a configuration report names beside
 *     the base ref, and what the run's law and pin guard bind to
 */
public record BoundTaskTier(LoadOutcome outcome, ObjectId lawCommit) {}
