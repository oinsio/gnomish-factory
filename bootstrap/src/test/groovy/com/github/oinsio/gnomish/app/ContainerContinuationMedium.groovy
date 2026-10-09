package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor
import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.DenialCursorSource
import com.github.oinsio.gnomish.adapter.git.EnvironmentAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitObjectsTaskRepository
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.LocalBoxEnvironment
import com.github.oinsio.gnomish.adapter.git.SandboxRoundEnvironmentSource
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.agent.AgentProgressListener
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.gitobjects.CommitIdentity
import com.github.oinsio.gnomish.gitobjects.CommitMetadata
import com.github.oinsio.gnomish.gitobjects.CommitRequest
import com.github.oinsio.gnomish.gitobjects.GitObjects
import com.github.oinsio.gnomish.gitobjects.TreeEdit
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import com.github.oinsio.gnomish.sandbox.environment.ScriptedSandboxDocker
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The container medium of the identity specs (task 5.3 of make-checkpoint-gate-durable): a factory
 * clone whose task branch is written as bare objects, with a converged bare origin — converged on
 * purpose, the subject is the continuation's write, not base resolution.
 *
 * <p><b>The park.</b> A fresh container run cannot close a round daemon-free — over the scripted
 * docker a round never lands its snapshot ({@code ContainerResumeRunnerSpec}) — so the run is
 * driven one layer down, the way {@code RoundTokenIdentitySpec} drives it: the domain {@link
 * Engine} over the real round adapters ({@link CliStageExecutor} with the fake agent, {@link
 * SandboxRoundEnvironmentSource}, {@link EnvironmentAttemptPersistence}) and a {@link
 * LocalBoxEnvironment} for the box. The round commit that leaves the gate or the stop is therefore
 * the production one; the park is recorded through {@link ContainerRunSupport#recordPark}, the
 * terminal boundary's own write, which replicates it to origin.
 *
 * <p><b>The continuation.</b> {@link ContainerResumeRunner} — {@code run --resume} — over a support
 * whose boxes run on the scripted docker ({@code ContainerResumeSpecBase}'s seam): the arm lands
 * its one commit through the support's push-decorated repository before any round, and the round
 * after it aborts there, which the specs do not judge.
 *
 * <p>Claimless on purpose: {@code gnomish run} claims nothing, so neither the writers nor the
 * support hold a tenure ({@code ClaimlessGitBoundarySpec}, the plain-{@code run} reason).
 */
class ContainerContinuationMedium implements ContinuationMedium, BareGitRepoFixture, AppAssemblyFixture {

    private static final PipelineLaw LAW = PipelineLaw.ofContent(['instructions.md': 'build it\n'])

    private final Path root
    private final Path cloneDir
    private final Path origin
    private final GitProcessRunner runner = new GitProcessRunner()
    private final ScriptedSandboxDocker docker = new ScriptedSandboxDocker()
    private final SandboxProperties sandbox = new SandboxProperties('gnomish/img', null, null, null, [], [], false, null, null, null, null)

    ContainerContinuationMedium(Path root) {
        this.root = Files.createDirectories(root)
        cloneDir = initWorkingRepo(root, 'clone')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(cloneDir, 'init')
        origin = addConvergedOrigin(cloneDir, root)
    }

    @Override
    String name() {
        'container'
    }

    @Override
    Path origin() {
        origin
    }

    @Override
    void park(String taskId, PipelineDefinition definition, String scenario) {
        def objects = objects()
        def first = TaskState.atStageStart(definition.stages().first().name())
        new GitObjectsTaskRepository(objects, ClaimEpochSource.NONE, DenialCursorSource.NONE).createTask(context(taskId),
                TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), first)
        def rounds = new CurrentRound()
        def box = new LocalBoxEnvironment(cloneDir, Files.createTempDirectory(root, 'box'))
        def lease = new EnvironmentLease({
            -> box
        }, TaskIdSanitizer.branchName(taskId), segments(definition))
        def source = new SandboxRoundEnvironmentSource(lease, runner, cloneDir, taskId, rounds, new VirtualClock())
        // Ahead of the wall clock the local box stamps process starts with, so wall time is positive.
        def executor = new CliStageExecutor(FakeAgentSupport.propertiesFor(scenario), new VirtualClock(Instant.now().plusSeconds(3600)),
                { e -> } as AgentProgressListener, LAW, source)
        def persistence = new EnvironmentAttemptPersistence(box, runner, cloneDir, objects, taskId, rounds, ClaimEpochSource.NONE)
        def clock = new VirtualClock()
        def ports = new EnginePorts(executor, new FilesExistCheckRunner(), new ScriptedCommandCheckRunner(),
                new ScriptedExternalCheckClient(), new ScriptedJudgeVoter(), new RecordingEventListener(), persistence,
                clock, new VirtualSleeper(clock))
        def outcome = new Engine().run(definition, context(taskId), first,
                new DirectoryWorkspace(Files.createTempDirectory(root, 'workspace')), ports)
        assert outcome instanceof TaskOutcome.Paused || outcome instanceof TaskOutcome.Escalated: "the run of ${taskId} did not park: ${outcome}"
        support(cloneDir, taskId, segments(definition)).recordPark(outcome, TrackerWrite.NONE)
    }

    @Override
    void plantStaleRequest(String taskId) {
        String ref = 'refs/heads/' + TaskIdSanitizer.branchName(taskId)
        def objects = objects()
        def tip = objects.resolveRef(ref).get()
        def identity = new CommitIdentity('gnome', 'gnome@sandbox.local')
        def now = Instant.now()
        objects.commit(new CommitRequest(ref, Optional.of(tip), tip,
                [
                    new TreeEdit.PutFile(BranchHistory.STALE_REQUEST, '{"question":"left over?","options":[]}'.getBytes('UTF-8'))
                ],
                new CommitMetadata(identity, now, identity, now, 'a request left on the tip')))
        gitOutput(cloneDir, 'push', 'origin', "${ref}:${ref}")
    }

    @Override
    void resume(String taskId, PipelineDefinition definition, String scenario, String decision) {
        def factory = { Path c, String t, List<Segment> s, d, List<String> creds ->
            support(c, t, s)
        } as ContainerSupportFactory
        def assembly = newAssembly(new ByteArrayInputStream(new byte[0]),
                new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8'), FakeAgentSupport.propertiesFor(scenario))
        try {
            new ContainerResumeRunner(assembly, TaskGitFixture.real(), 'taskId', factory)
                    .run(new RunOrder(cloneDir, null, definition, false), taskId, decision, segments(definition))
        } catch (AbortedException | RunParkedException ignored) {
            // The drive after the continuation ended (a scripted box never closes a round); the
            // continuation commit is already on origin.
        }
    }

    private ContainerRunSupport support(Path clone, String taskId, List<Segment> segments) {
        def environments = docker.environments(TaskIdSanitizer.sanitize(taskId), clone, sandbox, root.resolve('guard'))
        new ContainerRunSupport(runner, clone, taskId, environments, segments, SandboxLifecyclePass.NONE, ClaimEpochSource.NONE)
    }

    private GitObjects objects() {
        GitObjects.open(cloneDir.resolve('.git'), Files.createTempDirectory(root, 'index'))
    }

    private static List<Segment> segments(PipelineDefinition definition) {
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), definition.stages())
        ]
    }
}
