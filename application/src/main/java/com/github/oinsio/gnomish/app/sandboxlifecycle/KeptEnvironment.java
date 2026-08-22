package com.github.oinsio.gnomish.app.sandboxlifecycle;

import java.time.Duration;

/**
 * One kept environment observed by a sweep tick: the task whose stopped-but-preserved objects are
 * waiting for a resume, how old they are, and how much margin is left before the aged reaper
 * disposes them (NFR-O1 of add-serve-sandbox-lifecycle).
 *
 * <p>{@code untilReap} is computed here rather than carried by {@link SweepVerdict}: the verdict
 * event deliberately carries only what the decision matrix measured (age), while the reap
 * threshold is configuration the sink knows — so the margin is the sink's subtraction, not a
 * seventh field every entry point would have to fill.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O1 of add-serve-sandbox-lifecycle.
 *
 * @param taskKey the base task key the kept objects belong to; never blank
 * @param age how old the kept environment is; never negative
 * @param untilReap how long until the aged reaper disposes it; never negative
 */
public record KeptEnvironment(String taskKey, Duration age, Duration untilReap) {}
