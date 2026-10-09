package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.time.InstantSource;
import org.springframework.beans.factory.ObjectProvider;
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
 * collapse-composition-roots; FR3, FR9, FR10 of add-project-registry; FR18 of
 * supervise-daemon-loops-and-embed-dashboard.
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
            SandboxProperties sandboxProperties, FactoryProperties factoryProperties, InstantSource instantSource) {
        return SandboxLifecyclePassFactory.create(sandboxProperties, factoryProperties, instantSource);
    }

    /**
     * The equipment {@code take} and {@code serve} share, which each command builds its slot wiring
     * from once its tracker is bound (design D9 of collapse-composition-roots). The container
     * support stamps {@code tracked}: both commands claim through the tracker. The slots work in the
     * registered clone, read lazily because the bean exists only once a project was resolved (design
     * D9 of add-project-registry). It takes no time of its own: every slot's time — the abort
     * stamp, the terminal-write retry, the resume stamps — is the assembly's, the root's one time
     * equipment (task 3.9 of supervise-daemon-loops-and-embed-dashboard).
     */
    @Bean
    SlotWiringFactory slotWiringFactory(
            ManualRunAssembly manualRunAssembly,
            ObjectProvider<RegisteredClone> registeredClone,
            ContainerSupports containerSupports,
            TrackerWiring trackerWiring) {
        return new SlotWiringFactory(
                manualRunAssembly,
                registeredClone,
                ManualRunRunner.TASK_ID_KEY,
                containerSupports.takeSupport(),
                trackerWiring.pipelineSource());
    }

    /**
     * The serve daemon's leaf builders over its fixed equipment (design D7 of
     * collapse-composition-roots), with the clone the configuration loader resolved, read lazily
     * because the bean exists only once a project was resolved (design D9 of add-project-registry).
     */
    @Bean
    ServeAssembly serveAssembly(
            FactoryProperties factoryProperties,
            ServeProperties serveProperties,
            TimeEquipment timeEquipment,
            ObjectProvider<RegisteredClone> registeredClone) {
        return new ServeAssembly(factoryProperties, serveProperties, timeEquipment, registeredClone);
    }

    /**
     * The page inside {@code serve} (design D11, D12 of supervise-daemon-loops-and-embed-dashboard):
     * handed the tracker wiring as its {@link BoardReaders} role only, so the credential seam
     * behind it gains no second holder (NFR-S1).
     */
    @Bean
    ServeDashboard serveDashboard(
            TrackerWiring trackerWiring,
            ProjectScope projectScope,
            FactoryProperties factoryProperties,
            TimeEquipment timeEquipment) {
        return new ServeDashboard(trackerWiring, projectScope, factoryProperties, timeEquipment);
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
            SandboxLifecyclePass sandboxLifecyclePass,
            SandboxProperties sandboxProperties,
            ServeDashboard serveDashboard) {
        return new ServeRuntimeAssembly(
                slotWiringFactory, serveAssembly, git, sandboxLifecyclePass, sandboxProperties, serveDashboard);
    }

    /**
     * {@code take}'s production seams, with the installation's {@link ServeProperties} for batch
     * mode (FR2 of add-factory-serve: "the N limit applies to batch and serve") and the process
     * time equipment.
     */
    @Bean
    TakeCommandSeams takeCommandSeams(ServeProperties serveProperties, TimeEquipment timeEquipment) {
        return TakeCommandSeams.defaults(timeEquipment).withServeProperties(serveProperties);
    }

    /**
     * {@code gnomish take}, working in the registered clone the configuration loader resolved and
     * claiming under an instance id that names its project (FR3, FR10 of add-project-registry).
     */
    @Bean
    TakeCommand takeCommand(
            SlotWiringFactory slotWiringFactory,
            TaskGit git,
            FactoryProperties factoryProperties,
            ProjectScope projectScope,
            TrackerWiring trackerWiring,
            TakeCommandSeams takeCommandSeams,
            SandboxLifecyclePass sandboxLifecyclePass) {
        return new TakeCommand(
                slotWiringFactory,
                git,
                factoryProperties,
                projectScope,
                trackerWiring,
                takeCommandSeams,
                sandboxLifecyclePass);
    }

    /** {@code gnomish serve}, driving the real {@link FeedAutomaton#run}. */
    @Bean
    ServeCommand serveCommand(
            ServeRuntimeAssembly serveRuntimeAssembly,
            TaskGit git,
            ProjectScope projectScope,
            ServeProperties serveProperties,
            TrackerWiring trackerWiring,
            @Qualifier("errorConsoleIO") ConsoleIO errorConsoleIO) {
        return new ServeCommand(
                serveRuntimeAssembly,
                git,
                projectScope,
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
