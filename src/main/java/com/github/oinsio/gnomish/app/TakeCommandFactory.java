package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.pipeline.TrackerSubsectionValidator;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Builds {@link TakeCommand} instances, defaulting the test-seam collaborators the production wiring
 * ({@link ManualRunRunner}) and most take specs never override to {@link TakeCommandSeams#DEFAULTS}.
 * Extracted from {@link TakeCommand} so that class keeps a single canonical constructor. A spec that
 * needs to override a seam builds a {@link TakeCommandSeams} from {@code DEFAULTS} and layers on only
 * the fields it cares about (e.g. {@code TakeCommandSeams.DEFAULTS.withHeartbeatSleeper(sleeper)}).
 */
final class TakeCommandFactory {

    private TakeCommandFactory() {}

    /** Production wiring: all seams at their {@link TakeCommandSeams#DEFAULTS} values. */
    static TakeCommand of(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            String taskIdMdcKey,
            FactoryProperties factoryProperties,
            Clock clock,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            Map<String, TrackerSubsectionValidator> trackerValidatorRegistry) {
        return of(
                assembly,
                worktreesRoot,
                taskIdMdcKey,
                factoryProperties,
                clock,
                trackerAdapterRegistry,
                trackerValidatorRegistry,
                TakeCommandSeams.DEFAULTS);
    }

    /** Explicit seams (task 6.1, task 6.2, task 6.6, fix-reaper-idle-liveness FR5): lets a spec
     * override exactly the collaborators it needs, defaulting the rest via {@link TakeCommandSeams}. */
    static TakeCommand of(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            String taskIdMdcKey,
            FactoryProperties factoryProperties,
            Clock clock,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            Map<String, TrackerSubsectionValidator> trackerValidatorRegistry,
            TakeCommandSeams seams) {
        return new TakeCommand(
                assembly,
                worktreesRoot,
                taskIdMdcKey,
                factoryProperties,
                clock,
                trackerAdapterRegistry,
                trackerValidatorRegistry,
                seams.heartbeatSleeper(),
                seams.reaperSleeper(),
                seams.heartbeatMonotonicTime(),
                seams.takeoverConfirmation(),
                seams.serveProperties());
    }
}
