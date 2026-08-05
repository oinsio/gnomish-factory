package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.adapter.engine.SystemClock;
import com.github.oinsio.gnomish.adapter.pipeline.TrackerSubsectionValidator;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Builds the {@link SubcommandDispatch} for {@link ManualRunRunner}: wires the {@code take} command
 * ({@link TakeCommandFactory#of}) and the {@code serve} command ({@link ServeCommand}, driving
 * {@link FeedAutomaton#run}), then bundles them with the pre-built {@code status}/{@code usage}
 * commands. Extracted from {@link ManualRunRunner}'s constructor for file size; the runner keeps the
 * per-invocation assembly and the run-drive flow.
 *
 * <p>Implements FR9 of add-tracker-port; FR1 of add-factory-serve.
 */
final class SubcommandDispatchFactory {

    private SubcommandDispatchFactory() {}

    static SubcommandDispatch of(
            ManualRunAssembly assembly,
            Path worktreesRoot,
            Path homeDir,
            String taskIdMdcKey,
            FactoryProperties factoryProperties,
            ServeProperties serveProperties,
            Clock javaTimeClock,
            SystemClock systemClock,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            Map<String, TrackerSubsectionValidator> trackerValidatorRegistry,
            StatusCommand statusCommand,
            UsageCommand usageCommand) {
        var takeCommand = TakeCommandFactory.of(
                assembly,
                worktreesRoot,
                taskIdMdcKey,
                factoryProperties,
                javaTimeClock,
                trackerAdapterRegistry,
                trackerValidatorRegistry,
                TakeCommandSeams.DEFAULTS.withServeProperties(serveProperties));
        var serveCommand = new ServeCommand(
                assembly,
                worktreesRoot,
                homeDir,
                taskIdMdcKey,
                factoryProperties,
                serveProperties,
                javaTimeClock,
                systemClock,
                trackerAdapterRegistry,
                trackerValidatorRegistry,
                FeedAutomaton::run);
        return new SubcommandDispatch(statusCommand, usageCommand, takeCommand, serveCommand);
    }
}
