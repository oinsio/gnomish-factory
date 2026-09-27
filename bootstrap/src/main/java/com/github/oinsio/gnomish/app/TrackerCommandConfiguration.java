package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the tracker-driven commands, {@code take} and {@code serve}, and the subcommand
 * dispatch that routes to them beside the {@link ReportCommands} (design D6, D11 of
 * collapse-composition-roots). What used to be one static recipe the runner called from its
 * constructor is a set of bean methods, each at seven parameters or fewer; the two commands share
 * one {@link SlotWiringFactory} and one {@link SandboxLifecyclePass}, as they shared the one the
 * recipe built. Split from {@link ManualRunConfiguration}, which holds the infrastructure beans and
 * the manual-run drive: a responsibility of its own, the two subcommands that claim through a
 * tracker.
 *
 * <p>Implements FR9 of add-tracker-port; FR1 of add-factory-serve; FR1, FR7 of
 * collapse-composition-roots.
 */
@Configuration
public class TrackerCommandConfiguration {

    /**
     * The installation's sweep-lifecycle pass (FR6, NFR-O4 of add-serve-sandbox-lifecycle): {@code
     * take}'s pre-dispatch evaluation and {@code serve}'s tick run the same one; {@link
     * SandboxLifecyclePass#NONE} on a host-only install.
     */
    @Bean
    SandboxLifecyclePass sandboxLifecyclePass(
            SandboxProperties sandboxProperties, FactoryProperties factoryProperties, Clock javaTimeClock) {
        return SandboxLifecyclePassFactory.create(sandboxProperties, factoryProperties, javaTimeClock);
    }

    /**
     * The equipment {@code take} and {@code serve} share, which each command builds its slot wiring
     * from once its tracker is bound (design D9 of collapse-composition-roots). The container
     * support stamps {@code tracked}: both commands claim through the tracker.
     */
    @Bean
    SlotWiringFactory slotWiringFactory(
            ManualRunAssembly manualRunAssembly,
            FactoryPaths paths,
            Clock javaTimeClock,
            ContainerSupports containerSupports,
            TrackerWiring trackerWiring) {
        return new SlotWiringFactory(
                manualRunAssembly,
                paths.worktreesRoot(),
                ManualRunRunner.TASK_ID_KEY,
                javaTimeClock,
                containerSupports.takeSupport(),
                trackerWiring.pipelineSource());
    }

    /** The serve daemon's leaf builders over its fixed equipment (design D7 of collapse-composition-roots). */
    @Bean
    ServeAssembly serveAssembly(
            FactoryProperties factoryProperties, ServeProperties serveProperties, SystemClock systemClock) {
        return new ServeAssembly(factoryProperties, serveProperties, systemClock);
    }

    /**
     * The serve runtime assembly, built once over the daemon's fixed equipment and held by the
     * command (design D7 of collapse-composition-roots).
     */
    @Bean
    ServeRuntimeAssembly serveRuntimeAssembly(
            SlotWiringFactory slotWiringFactory,
            ServeAssembly serveAssembly,
            TaskGit git,
            FactoryPaths paths,
            Clock javaTimeClock,
            SandboxLifecyclePass sandboxLifecyclePass,
            SandboxProperties sandboxProperties) {
        return new ServeRuntimeAssembly(
                slotWiringFactory, serveAssembly, git, paths, javaTimeClock, sandboxLifecyclePass, sandboxProperties);
    }

    /**
     * {@code gnomish take}, with the production seams and the installation's {@link
     * ServeProperties} for batch mode (FR2 of add-factory-serve: "the N limit applies to batch and
     * serve").
     */
    @Bean
    TakeCommand takeCommand(
            SlotWiringFactory slotWiringFactory,
            TaskGit git,
            FactoryProperties factoryProperties,
            Clock javaTimeClock,
            TrackerWiring trackerWiring,
            ServeProperties serveProperties,
            SandboxLifecyclePass sandboxLifecyclePass) {
        return new TakeCommand(
                slotWiringFactory,
                git,
                factoryProperties,
                javaTimeClock,
                trackerWiring,
                TakeCommandSeams.DEFAULTS.withServeProperties(serveProperties),
                sandboxLifecyclePass);
    }

    /** {@code gnomish serve}, driving the real {@link FeedAutomaton#run}. */
    @Bean
    ServeCommand serveCommand(
            ServeRuntimeAssembly serveRuntimeAssembly,
            TaskGit git,
            FactoryProperties factoryProperties,
            ServeProperties serveProperties,
            TrackerWiring trackerWiring,
            @Qualifier("errorConsoleIO") ConsoleIO errorConsoleIO) {
        return new ServeCommand(
                serveRuntimeAssembly,
                git,
                factoryProperties,
                serveProperties,
                trackerWiring,
                FeedAutomaton::run,
                errorConsoleIO);
    }

    /** Routes every subcommand but {@code run} (FR13, FR14 of add-git-workflow; FR9 of add-tracker-port). */
    @Bean
    SubcommandDispatch subcommandDispatch(
            ReportCommands reportCommands, TakeCommand takeCommand, ServeCommand serveCommand) {
        return new SubcommandDispatch(reportCommands, takeCommand, serveCommand);
    }
}
