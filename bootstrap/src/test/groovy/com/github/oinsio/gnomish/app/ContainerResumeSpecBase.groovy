package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitObjectsTaskRepository
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages
import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
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
import com.github.oinsio.gnomish.sandbox.environment.ScriptedSandboxDocker
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Shared daemon-free fixture of the {@link ContainerResumeRunner} specs (FR6 of add-sandbox-core):
 * a real factory clone whose task branches carry {@code task.json}/{@code state.json} as bare git
 * objects, a resume runner whose per-run support runs over a {@link ScriptedSandboxDocker}, and
 * the branch-authoring helpers each recorded-outcome scenario starts from.
 */
abstract class ContainerResumeSpecBase extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    def gitRunner = new GitProcessRunner()
    def docker = new ScriptedSandboxDocker()
    def sandbox = new SandboxProperties('gnomish/img', null, null, null, [], [], false, null, null, null, null)
    Path cloneDir
    GitObjects gitObjects
    GitObjectsTaskRepository repository

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        Path index = tempDir.resolve('index')
        Files.createDirectories(index)
        gitObjects = GitObjects.open(cloneDir.resolve('.git'), index)
        repository = new GitObjectsTaskRepository(gitObjects)
    }

    protected static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    protected static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    protected static TaskContext context(String taskId) {
        new TaskContext(taskId, 'title', 'body', List.<Decision> of())
    }

    protected static TaskState pipelineEndState() {
        new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())
    }

    protected List<Segment> segments() {
        [
            new Segment(new AdapterBinding(BindingNames.CONTAINER, CapabilityPassport.container()), [stage()])
        ]
    }

    /** A resume runner whose per-run support runs over the scripted fake docker (seam ctor). */
    protected ContainerResumeRunner runner(InputStream input, PrintStream output) {
        def factory = { Path c, String t, List<Segment> s, SandboxProperties sp, fp, definition, List<String> creds ->
            def environments = docker.environments(
            TaskIdSanitizer.sanitize(t), c, sandbox, tempDir.resolve('guard'))
            new ContainerRunSupport(new GitProcessRunner(), c, t, environments, s, SandboxLifecyclePass.NONE)
        } as ContainerSupportFactory
        new ContainerResumeRunner(
                newAssembly(input, output), TaskGitFixture.real(), sandbox, testProperties(), 'taskId', factory)
    }

    protected void resume(String taskId, InputStream input, PrintStream output, boolean discardWork = false) {
        runner(input, output).run(
                cloneDir, taskId, pipeline(), segments(), RunArguments.InteractiveMode.ALL, discardWork)
    }

    protected static InputStream lines(String... answers) {
        new ByteArrayInputStream(((answers as List).join(System.lineSeparator())
                + System.lineSeparator() * 5).getBytes('UTF-8'))
    }

    protected static PrintStream sink() {
        new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8')
    }

    protected void commitOnBranch(String taskId, String path, byte[] bytes, String message) {
        String ref = 'refs/heads/' + TaskIdSanitizer.branchName(taskId)
        def tip = gitObjects.resolveRef(ref).get()
        def identity = new CommitIdentity('test', 'test@localhost')
        def now = Instant.now()
        gitObjects.commit(new CommitRequest(ref, Optional.of(tip), tip,
                [
                    new TreeEdit.PutFile(path, bytes)
                ],
                new CommitMetadata(identity, now, identity, now, message)))
    }

    /** Commits a state.json parked at PipelineEnd, so a resumed drive completes without a round. */
    protected void commitStateAtPipelineEnd(String taskId) {
        def bytes = TaskStateJson.mapper()
                .writeValueAsString(StateJsonMapper.toDto(pipelineEndState())).getBytes('UTF-8')
        commitOnBranch(taskId, '.gnomish-task/state.json', bytes, 'state')
    }

    /**
     * Commits a state.json positioned at {@code stage} as the tip's message being the
     * snapshot-commit shape {@link ServiceCommitMessages#snapshot} — resume then classifies the
     * tip as an interrupted verification (FR21, D15) instead of running ordinary salvage.
     */
    protected void commitSnapshotStateAtStage(String taskId, String stage, int round) {
        def bytes = TaskStateJson.mapper()
                .writeValueAsString(StateJsonMapper.toDto(TaskState.atStageStart(stage))).getBytes('UTF-8')
        commitOnBranch(taskId, '.gnomish-task/state.json', bytes, ServiceCommitMessages.snapshot(stage, round))
    }

    /** Hand-commits task.json (the crash-window shapes recordOutcome never leaves behind). */
    protected void commitTaskJson(String taskId, TaskOutcome outcome, EscalationReport lastEscalation) {
        def dto = TaskJsonMapper.toDto(
                context(taskId), gitOutput(cloneDir, 'rev-parse', 'HEAD').trim(), Instant.now(),
                outcome, lastEscalation, false)
        def bytes = TaskStateJson.mapper().writeValueAsString(dto).getBytes('UTF-8')
        commitOnBranch(taskId, '.gnomish-task/task.json', bytes, 'outcome')
    }

    protected String taskJsonBelowTip(String taskId) {
        gitOutput(cloneDir, 'show', TaskIdSanitizer.branchName(taskId) + '~1:.gnomish-task/task.json')
    }
}
