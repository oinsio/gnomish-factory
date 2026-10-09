package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.DefaultBranch
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.ScriptedSandboxDocker
import java.nio.file.Path
import java.time.Clock
import java.util.function.BooleanSupplier

/**
 * The real take routes a kill-point pickup runs through (NFR-R1, design D8 of
 * make-checkpoint-gate-durable): {@link TakeLoadedBranchRoutes} over the medium's own {@link
 * ResumeMechanics} — {@link HostResumeMechanics} over a {@link TakeResumeRunner}, or {@link
 * ContainerResumeMechanics} over a {@link TakeContainerResumeRunner} — assembled from a {@link
 * SlotWiring} the way {@code TakeResumeSpecBase} assembles it. It lives in this package because
 * the routes, the runners and the mechanics are package-private; the kill-point rows only call
 * the closure it returns.
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
     * @return {@code (TakeOrder) -> TakeResult}: one pickup through the host routes
     */
    Closure<TakeResult> host(
            RegisteredClone clone, TaskGit git, Tracker tracker, PipelineDefinition definition, FactoryProperties agent) {
        def runner = new TakeResumeRunner(wiring(clone, git, tracker, agent, ContainerTakeSupport.hostOnly()))
        routes(new HostResumeMechanics(runner, git, clone, definition), git)
    }

    /**
     * @param clone the registered factory clone whose task branch is written as bare objects
     * @param git the task-git bundle whose tenure record the support stamps from
     * @param tracker the tracker the routes' abort fuse reports to
     * @param definition the pinned pipeline, one container segment over all its stages
     * @param docker the scripted docker every box of the pickup runs on
     * @return {@code (TakeOrder) -> TakeResult}: one pickup through the container routes
     */
    Closure<TakeResult> container(
            RegisteredClone clone, TaskGit git, Tracker tracker, PipelineDefinition definition,
            ScriptedSandboxDocker docker) {
        Path guard = clone.clonePath().resolveSibling('guard')
        def factory = { Path c, String t, List<Segment> s, d, List<String> creds ->
            new ContainerRunSupport(new GitProcessRunner(), c, t,
            docker.environments(TaskIdSanitizer.sanitize(t), c, SANDBOX, guard), s, SandboxLifecyclePass.NONE,
            git.epochs())
        } as ContainerSupportFactory
        def hostOnly = ContainerTakeSupport.hostOnly()
        def support = new ContainerTakeSupport(hostOnly.bindingProperties(), SANDBOX, hostOnly.bindingRegistry(),
                { -> true } as BooleanSupplier, factory)
        def runner = new TakeContainerResumeRunner(wiring(clone, git, tracker, testProperties(), support))
        routes(new ContainerResumeMechanics(runner, segments(definition), definition), git)
    }

    /** The one container segment the routes run every stage of {@code definition} in. */
    static List<Segment> segments(PipelineDefinition definition) {
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), definition.stages())
        ]
    }

    private static <B extends ResumedBranch> Closure<TakeResult> routes(ResumeMechanics<B> mechanics, TaskGit git) {
        def routes = new TakeLoadedBranchRoutes<B>(mechanics, new TakeDecisionResume<B>(mechanics), git)
        return { TakeOrder order -> routes.route(order) }
    }

    private SlotWiring wiring(
            RegisteredClone clone, TaskGit git, Tracker tracker, FactoryProperties agent, ContainerTakeSupport support) {
        def assembly = newAssembly(new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8'), agent)
        new SlotWiring(assembly, git, clone, MDC_KEY,
                new AbortFuse(new AbortHandler(tracker, Clock.systemUTC()), 3), [], support,
                new ClaimTenure(ClaimBeat.NONE, new ClaimLossFlag()),
                new TrustedBaseContext(BaseDefinition.none(), new DefaultBranch(currentBranch(clone.clonePath()))))
    }
}
