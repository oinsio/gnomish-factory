package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.environment.ScriptedSandboxDocker
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, FR12, FR21, FR25, D19 of add-sandbox-core: {@link ContainerGitModeRunner}'s fresh-task
 * bootstrap, daemon-free over a scripted fake docker CLI — the branch/environment banner prints
 * both lines, the task branch is seeded from the resolved {@code --base} (or the clone's HEAD
 * when absent), and the run actually drives the engine loop to a terminal boundary rather than
 * returning right after branch creation.
 */
class ContainerGitModeRunnerSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    static final String KEY = 't-fresh'

    @TempDir
    Path tempDir

    def docker = new ScriptedSandboxDocker()
    def sandbox = new SandboxProperties('gnomish/img', null, null, null, [], [], false)
    Path cloneDir

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        commitAll(cloneDir, 'init')
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    private static List<Segment> segments() {
        [
            new Segment(AdapterBinding.CONTAINER, [stage()])
        ]
    }

    /**
     * A read-only {@link ContainerRunSupport} over the same bare-object clone {@code run()} just
     * wrote to, for reading back {@code task.json} — a fresh {@link
     * com.github.oinsio.gnomish.gitobjects.GitObjects GitObjects} handle over the same {@code
     * .git} directory, docker-independent.
     */
    private ContainerRunSupport readBack(String taskId) {
        def environments = docker.environments(KEY, cloneDir, sandbox, tempDir.resolve('guard'))
        new ContainerRunSupport(new GitProcessRunner(), cloneDir, taskId, environments, segments())
    }

    /**
     * Drives one fresh container-mode run through the seam constructor over the scripted fake
     * docker (mirroring {@code ContainerResumeSpecBase.runner}) — fully daemon-free, including the
     * runner-start orphan sweep (FR11), whose read-only listings the fake answers empty. Per
     * {@code ContainerTerminalDriveSpec}, an interactive round never closes with a snapshot
     * commit, so the sandboxed persistence always aborts the round; every call below is expected
     * to raise {@link AbortedException}, that abort itself proving {@code run()} reached the loop.
     */
    private void run(String taskId, String base, PrintStream output, InputStream input = lines()) {
        def factory = { Path c, String t, List<Segment> s, SandboxProperties sp, fp, definition, List<String> creds ->
            def environments = docker.environments(TaskIdSanitizer.sanitize(t), c, sandbox, tempDir.resolve('guard'))
            new ContainerRunSupport(new GitProcessRunner(), c, t, environments, s)
        } as ContainerSupportFactory
        def runner = new ContainerGitModeRunner(
                newAssembly(input, output), TaskGitFixture.real(), sandbox, testProperties(), factory)
        runner.run(cloneDir, base, pipeline(), segments(), context(taskId), TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.ALL)
    }

    private static TaskContext context(String taskId) {
        new TaskContext(taskId, 'title', 'body', List.<Decision> of())
    }

    private static InputStream lines(String... answers) {
        new ByteArrayInputStream((((answers as List) + ['']).join(System.lineSeparator())
        + System.lineSeparator() * 5).getBytes('UTF-8'))
    }

    // FR3, UX1: both banner lines print before the branch/environment identity is used further —
    // a mutant that drops either println still leaves the other one, so both are asserted. The
    // banner writes directly to System.out (the process's own console, not the injected dialog
    // console), so — like GitModeRunnerSpec's own banner test — this redirects System.out itself.
    def "run() prints both the branch and environment banner lines before driving the pipeline"() {
        given:
        def originalOut = System.out
        def captured = new ByteArrayOutputStream()
        System.out = new PrintStream(captured, true, 'UTF-8')

        when:
        run('T-BANNER', null, sink())

        then: 'the interactive round aborts (no attempt commit closes it), but only after both lines print'
        thrown(AbortedException)
        def text = captured.toString('UTF-8')
        text.contains('container mode: branch gnomish/T-BANNER')
        text.contains('container mode: environment T-BANNER')

        cleanup:
        System.out = originalOut
    }

    // FR3: no --base resolves to the clone's current HEAD, exactly like GitFreshTaskSupport's
    // host-mode twin — the negated-conditional mutant of the ternary would instead seed from a
    // non-existent "null" ref (base == null branch flipped) and fail outright.
    def "run() with no --base seeds the task branch from the clone's current HEAD"() {
        given:
        def head = gitOutput(cloneDir, 'rev-parse', 'HEAD').trim()

        when:
        run('T-NOBASE', null, sink())

        then:
        thrown(AbortedException)
        readBack('T-NOBASE').readTaskJson().baseCommit() == head
    }

    // FR3: an explicit --base is used verbatim instead of HEAD — the two commits are made to
    // differ so a mutant collapsing both ternary branches to the same ref cannot pass both tests.
    def "run() with an explicit --base seeds the task branch from that ref, not HEAD"() {
        given: 'a second commit moves HEAD away from the ref that will be passed as --base'
        def rootSha = gitOutput(cloneDir, 'rev-parse', 'HEAD').trim()
        Files.writeString(cloneDir.resolve('second.txt'), 'more\n')
        commitAll(cloneDir, 'second')
        def head = gitOutput(cloneDir, 'rev-parse', 'HEAD').trim()
        assert head != rootSha

        when:
        run('T-BASE', rootSha, sink())

        then:
        thrown(AbortedException)
        def baseCommit = readBack('T-BASE').readTaskJson().baseCommit()
        baseCommit == rootSha
        baseCommit != head
    }

    // FR3, D19: run() does not stop at branch creation — it actually drives the engine loop to the
    // point where the terminal boundary records an outcome. A mutant that removes the
    // ContainerTerminalDrive.run call would leave the branch parked right after creation — no
    // AbortedException, no outcome ever recorded — and this test alone would fail on both counts.
    def "run() drives past branch creation into the engine loop, which records the aborted outcome"() {
        when:
        run('T-DRIVE', null, sink())

        then:
        thrown(AbortedException)
        readBack('T-DRIVE').readTaskJson().outcome() instanceof RecordedOutcome.Aborted
    }

    private static PrintStream sink() {
        new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8')
    }
}
