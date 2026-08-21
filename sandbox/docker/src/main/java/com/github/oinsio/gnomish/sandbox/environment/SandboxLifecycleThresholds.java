package com.github.oinsio.gnomish.sandbox.environment;

import java.time.Duration;

/**
 * The three configurable durations {@link SandboxLifecycleSweep} decides by (`sandbox-lifecycle`,
 * UX4 "no rebuild to tune"): an object younger than {@code minimumAge} is never touched
 * regardless of verdict; a kept (stopped, or container-less remnant) object is disposed once its
 * age exceeds {@code keptReapAge} (default 7 days); an unowned-by-age manual running box is
 * stopped once its age exceeds {@code manualRunningStopAge} (default 24 hours). Config-key
 * binding to these three values is bootstrap's concern (task 4.x); this record just carries them
 * to the pure decision points.
 *
 * @param minimumAge the creation-race protection window; never null
 * @param keptReapAge the aged-reaper threshold for stopped/remnant objects; never null
 * @param manualRunningStopAge the manual-mode running-stop threshold; never null
 */
public record SandboxLifecycleThresholds(Duration minimumAge, Duration keptReapAge, Duration manualRunningStopAge) {}
