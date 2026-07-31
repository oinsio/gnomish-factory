package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper;
import com.github.oinsio.gnomish.adapter.pipeline.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.lease.MonotonicTime;
import com.github.oinsio.gnomish.app.lease.SystemMonotonicTime;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Builds {@link TakeCommand} instances, defaulting the test-seam collaborators the production wiring
 * ({@link ManualRunRunner}) and most take specs never override — the beat {@link Sleeper} (task 6.1),
 * the reaper's {@link MonotonicTime} (task 6.6), and the {@link TakeoverConfirmation} (task 6.2).
 * Extracted from {@link TakeCommand} so that class keeps a single canonical constructor; the four
 * {@code of} overloads replace the former telescoping constructors one-for-one.
 */
final class TakeCommandFactory {

    private TakeCommandFactory() {}

    /** Production wiring: real {@link ThreadSleeper} beat, real {@link SystemMonotonicTime}, TTY-detecting takeover. */
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
                new ThreadSleeper());
    }

    /** Explicit beat sleeper (task 6.1); production monotonic time and TTY-detecting takeover. */
    static TakeCommand of(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            String taskIdMdcKey,
            FactoryProperties factoryProperties,
            Clock clock,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            Map<String, TrackerSubsectionValidator> trackerValidatorRegistry,
            Sleeper heartbeatSleeper) {
        return of(
                assembly,
                worktreesRoot,
                taskIdMdcKey,
                factoryProperties,
                clock,
                trackerAdapterRegistry,
                trackerValidatorRegistry,
                heartbeatSleeper,
                ConsoleTakeoverConfirmation.systemTty());
    }

    /** Explicit beat sleeper and takeover (task 6.2); production monotonic time, unaffected by task 6.6. */
    static TakeCommand of(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            String taskIdMdcKey,
            FactoryProperties factoryProperties,
            Clock clock,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            Map<String, TrackerSubsectionValidator> trackerValidatorRegistry,
            Sleeper heartbeatSleeper,
            TakeoverConfirmation takeoverConfirmation) {
        return of(
                assembly,
                worktreesRoot,
                taskIdMdcKey,
                factoryProperties,
                clock,
                trackerAdapterRegistry,
                trackerValidatorRegistry,
                heartbeatSleeper,
                new SystemMonotonicTime(),
                takeoverConfirmation);
    }

    /** Fully explicit (task 6.6): a controlled-clock integration test steps a held claim past its TTL. */
    static TakeCommand of(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            String taskIdMdcKey,
            FactoryProperties factoryProperties,
            Clock clock,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            Map<String, TrackerSubsectionValidator> trackerValidatorRegistry,
            Sleeper heartbeatSleeper,
            MonotonicTime heartbeatMonotonicTime,
            TakeoverConfirmation takeoverConfirmation) {
        return new TakeCommand(
                assembly,
                worktreesRoot,
                taskIdMdcKey,
                factoryProperties,
                clock,
                trackerAdapterRegistry,
                trackerValidatorRegistry,
                heartbeatSleeper,
                heartbeatMonotonicTime,
                takeoverConfirmation);
    }
}
