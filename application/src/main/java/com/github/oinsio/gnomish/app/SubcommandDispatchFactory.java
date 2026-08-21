package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource;
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;

/**
 * Builds the {@link SubcommandDispatch} for {@code ManualRunRunner}: wires the {@code take} command
 * ({@link TakeCommandFactory#of}) and the {@code serve} command ({@link ServeCommand}, driving
 * {@link FeedAutomaton#run}), then bundles them with the pre-built {@code status}/{@code
 * usage}/{@code board}/{@code dashboard} commands. Extracted from {@code ManualRunRunner}'s
 * constructor for file size; the runner keeps the per-invocation assembly and the run-drive flow.
 *
 * <p>Implements FR9 of add-tracker-port; FR1 of add-factory-serve; FR1 of add-board-command; FR1
 * of add-dashboard-page.
 */
final class SubcommandDispatchFactory {

    private SubcommandDispatchFactory() {}

    static SubcommandDispatch of(
            RunAssembly assembly,
            TaskGit git,
            Path worktreesRoot,
            Path homeDir,
            String taskIdMdcKey,
            FactoryProperties factoryProperties,
            ServeProperties serveProperties,
            Clock javaTimeClock,
            SystemClock systemClock,
            Map<String, TrackerAdapterFactory> trackerAdapterRegistry,
            SecretsProvider secretsProvider,
            PipelineSource pipelineSource,
            StatusCommand statusCommand,
            UsageCommand usageCommand,
            BoardCommand boardCommand,
            DashboardCommand dashboardCommand,
            SandboxLifecyclePass sandboxLifecyclePass,
            ContainerTakeSupport containerTakeSupport) {
        var takeCommand = TakeCommandFactory.of(
                assembly,
                git,
                worktreesRoot,
                taskIdMdcKey,
                factoryProperties,
                javaTimeClock,
                trackerAdapterRegistry,
                secretsProvider,
                pipelineSource,
                TakeCommandSeams.DEFAULTS.withServeProperties(serveProperties),
                sandboxLifecyclePass,
                containerTakeSupport);
        var serveCommand = new ServeCommand(
                assembly,
                git,
                worktreesRoot,
                homeDir,
                taskIdMdcKey,
                factoryProperties,
                serveProperties,
                javaTimeClock,
                systemClock,
                trackerAdapterRegistry,
                secretsProvider,
                pipelineSource,
                FeedAutomaton::run,
                sandboxLifecyclePass,
                containerTakeSupport);
        return new SubcommandDispatch(
                statusCommand, usageCommand, takeCommand, serveCommand, boardCommand, dashboardCommand);
    }
}
