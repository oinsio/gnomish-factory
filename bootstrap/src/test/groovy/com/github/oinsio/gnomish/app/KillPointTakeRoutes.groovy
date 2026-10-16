package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.ContainerBindingProvider
import com.github.oinsio.gnomish.sandbox.environment.ScriptedSandboxDocker
import java.nio.file.Path

/**
 * The real take entries a kill-point pickup runs through (NFR-R1, design D8 of
 * make-checkpoint-gate-durable; design D3 of fix-terminal-receipt-convergence): {@link
 * TakeLoadedBranchRoutes} over the medium's own {@link ResumeMechanics} — {@link
 * HostResumeMechanics} over a {@link TakeResumeRunner}, or {@link ContainerResumeMechanics} over a
 * {@link TakeContainerResumeRunner} — for a claimed task, and {@link TakeDisposition} for a task
 * the tracker reports finished, both assembled from one {@link SlotWiring} the way {@code
 * TakeResumeSpecBase} assembles it. It lives in this package because the routes, the disposition,
 * the runners and the mechanics are package-private; the kill-point rows only call the closures of
 * the {@link Entries} it returns.
 *
 * <p>The container routes run their boxes on a {@link ScriptedSandboxDocker}, daemon-free: a box
 * is materialized and salvaged there, but an agent round cannot close in it — which is why the
 * docker's recorded {@code starts} is the container's recording executor.
 */
final class KillPointTakeRoutes implements AppAssemblyFixture, BareGitRepoFixture {

    private static final String MDC_KEY = 'taskId'

    private static final SandboxProperties SANDBOX =
    new SandboxProperties('gnomish/img', null, null, null, [], [], false, null, null, null, null)

    /**
     * @param clone the registered factory clone the task's worktree lives under
     * @param git the task-git bundle whose tenure record the world's writers stamp from
     * @param tracker the tracker the routes' abort fuse reports to
     * @param definition the pinned pipeline the mechanics approve a gate against
     * @param agent the agent the engine would run — a recording one in the kill-point rows
     * @return the host take entries
     */
    Entries host(
            RegisteredClone clone, TaskGit git, Tracker tracker, PipelineDefinition definition, FactoryProperties agent) {
        def wiring = wiring(clone, git, tracker, agent, ContainerTakeSupportFixture.hostOnly())
        entries(new HostResumeMechanics(new TakeResumeRunner(wiring), git, clone, definition), wiring)
    }

    /**
     * @param clone the registered factory clone whose task branch is written as bare objects
     * @param git the task-git bundle whose tenure record the support stamps from
     * @param tracker the tracker the routes' abort fuse reports to
     * @param definition the pinned pipeline, one container segment over all its stages
     * @param docker the scripted docker every box of the pickup runs on
     * @return the container take entries
     */
    Entries container(
            RegisteredClone clone, TaskGit git, Tracker tracker, PipelineDefinition definition,
            ScriptedSandboxDocker docker) {
        Path guard = clone.clonePath().resolveSibling('guard')
        def factory = { Path c, String t, List<Segment> s, d, List<String> creds ->
            new ContainerRunSupport(new GitProcessRunner(), c, t,
            docker.environments(TaskIdSanitizer.sanitize(t), c, SANDBOX, guard), s, SandboxLifecyclePass.NONE,
            git.epochs())
        } as ContainerSupportFactory
        // The routes run the container mechanics directly, never the selector; the finished-task
        // disposition asks it which medium cleans, so it plans the container mode, daemon-free.
        def support = new ContainerTakeSupport(containerSelector(), factory)
        def wiring = wiring(clone, git, tracker, testProperties(), support)
        entries(new ContainerResumeMechanics(new TakeContainerResumeRunner(wiring), segments(definition), definition), wiring)
    }

    /** The one container segment the routes run every stage of {@code definition} in. */
    static List<Segment> segments(PipelineDefinition definition) {
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), definition.stages())
        ]
    }

    /**
     * The routes over {@code wiring}'s own git and terminal-write retry, as {@code TakeWorkRouter}
     * builds them, and the disposition over the same slot, as {@code TakeCommands} builds it.
     */
    private static <B extends ResumedBranch> Entries entries(ResumeMechanics<B> mechanics, SlotWiring wiring) {
        def routes = new TakeLoadedBranchRoutes<B>(mechanics, new TakeDecisionResume<B>(mechanics), wiring.git(),
                wiring.terminalWriteRetry())
        def disposition = new TakeDisposition(wiring, false, TakeoverConfirmation.UNAVAILABLE)
        new Entries({ TakeOrder order ->
            routes.route(order)
        }, { TakeOrder order ->
            disposition.dispose(order)
        })
    }

    /** A selector that plans the container mode with no daemon: an image, and a runtime probe that answers yes. */
    private static SandboxModeSelector containerSelector() {
        def registry = AdapterBindingRegistry.ratified([
            new ContainerBindingProvider()
        ], BindingTrustTable.firstParty())
        new SandboxModeSelector(new BindingProperties(BindingNames.CONTAINER, [:]), SANDBOX, registry,
        { true } as ContainerRuntimeProbe)
    }

    private SlotWiring wiring(
            RegisteredClone clone, TaskGit git, Tracker tracker, FactoryProperties agent, ContainerTakeSupport support) {
        // The slot's one time, virtual: its abort stamps and its terminal-write retry derive from it.
        def assembly = newAssembly(new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8'), agent, VirtualTimeEquipment.create())
        new SlotWiring(assembly, git, clone, MDC_KEY,
                new AbortFuse(new AbortHandler(tracker, assembly.timeEquipment().clock()), 3), [], support,
                new ClaimTenure(ClaimBeat.NONE, new ClaimLossFlag()),
                new TrustedBaseContext(BaseDefinition.none(), new DefaultBranch(currentBranch(clone.clonePath()))))
    }

    /**
     * One medium's two take entries, each {@code (TakeOrder) -> TakeResult}: {@link #route} for a
     * task this order has claimed, {@link #dispose} for the explicit-mode disposition of a task as
     * the order fetched it.
     */
    static final class Entries {

        final Closure<TakeResult> route
        final Closure<TakeResult> dispose

        Entries(Closure<TakeResult> route, Closure<TakeResult> dispose) {
            this.route = route
            this.dispose = dispose
        }
    }
}
