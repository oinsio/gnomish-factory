package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.e2e.gitea.GiteaContainerFixture
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.AutoCleanup
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * NFR-R3, U2, G2 of add-git-workflow (task 6.6): "for other instances, unpushed work does not
 * exist — resume semantics rely only on what reached origin." Two separate local clones stand in
 * for two separate factory instances (U2's "second instance / another machine" scenario), both
 * pointed at the same Gitea repo as {@code origin} but sharing no local git state whatsoever:
 * instance A creates the task and completes a round, pushing it via {@link
 * com.github.oinsio.gnomish.adapter.git.BestEffortPush}; instance B is a fresh {@code git clone}
 * of the Gitea repo made only after A's push, so it can only see the task branch by locating it
 * on {@code origin} (FR8, {@link com.github.oinsio.gnomish.adapter.git.TaskBranchLocator}).
 *
 * <p>Reuses {@link GiteaContainerFixture} exactly as bootstrapped by task 6.5's harness spec.
 *
 * <p>Implements NFR-R3 of add-git-workflow.
 */
@Timeout(value = 180, unit = java.util.concurrent.TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unreachable — see GiteaAvailability; Docker is a dev/CI prerequisite for the Gitea E2E layer (.claude/rules/testing.md)')
class GiteaCrossInstanceResumeE2ESpec extends Specification implements BareGitRepoFixture {

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture()

    @TempDir
    Path tempDir

    def gitRunner = new GitProcessRunner()

    def setupSpec() {
        gitea.start()
    }

    private static StageDefinition stage(String name) {
        new StageDefinition(
                name, 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    /** Two stages: instance A's own round only completes stage "build", never the whole task —
     * so the run stops mid-task with no Completed/cleanup commit of its own (that commit is not
     * pushed, per design D11's push scope of "after every round"); instance B then resumes and
     * finishes stage "verify" to completion, producing its own round push. */
    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            stage('build'),
            stage('verify')
        ])
    }

    private static TaskContext context(String taskId) {
        new TaskContext(taskId, 'title', 'body', List.<Decision> of())
    }

    private static ManualRunAssembly assembly(InputStream input = new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8'))) {
        new ManualRunAssembly(
                new SystemConsoleIO(input, System.out),
                new FilesExistCheckRunner(),
                new ShellCommandCheckRunner(),
                new SystemClock(),
                new ThreadSleeper(),
                new FactoryProperties('test-instance', null, null))
    }

    /** A brand-new, independent local clone of the Gitea repo — stands in for a separate machine. */
    private Path freshClone(String name) {
        Path dir = tempDir.resolve(name)
        gitRunner.run(tempDir, 'clone', gitea.authenticatedCloneUrl(), dir.toString())
        dir
    }

    // NFR-R3, U2: instance B never shares local git state with instance A — it only clones the
    // Gitea repo AFTER A's push — yet it resumes the same task and sees A's pushed round; its own
    // further round also lands on the shared origin, provable via a third, independent clone.
    def "a second instance, in a fresh clone with no local knowledge of the first, resumes and continues the task from origin"() {
        given: 'instance A: a clone with a project history, seeded on origin'
        def instanceA = freshClone('instance-a')
        Files.writeString(instanceA.resolve('instructions.md'), 'build it\n')
        gitRunner.run(instanceA, 'add', 'instructions.md')
        gitRunner.run(instanceA, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        gitRunner.run(instanceA, 'push', 'origin', 'HEAD:refs/heads/main')
        def worktreesA = tempDir.resolve('worktrees-a')
        def taskId = 'CROSS-1'

        when: 'instance A completes only the first stage\'s round, then its stdin runs out mid-second-stage: only one Enter is supplied, enough for "build" to pass and advance, not enough for "verify" to also complete — simulating a died process (GitModeRunner deliberately leaves such an exit without any outcome write, per its own javadoc)'
        new GitModeRunner(assembly(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8'))), worktreesA)
                .run(instanceA, null, pipeline(), context(taskId), TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.ALL)

        then: 'stdin exhaustion propagates — the task stopped mid-run, with only the first round durably committed'
        thrown(InputExhaustedException)
        def tipAfterA = gitRunner.run(instanceA, 'rev-parse', "gnomish/${taskId}").stdout().trim()
        tipAfterA

        when: 'instance B: a completely separate fresh clone, made only now — after A already pushed'
        def instanceB = freshClone('instance-b')
        def worktreesB = tempDir.resolve('worktrees-b')

        and: 'instance B resumes the same task purely via what reached origin (FR8/D9 locate -> fetch)'
        def bundle = new GitResumeRunner(assembly(), worktreesB, 'taskId').bootstrap(instanceB, taskId)

        then: 'instance B sees exactly the round instance A pushed, without ever touching instance A locally'
        Files.exists(bundle.worktreePath().resolve('.gnomish-task').resolve('task.json'))
        gitRunner.run(instanceB, 'rev-parse', "gnomish/${taskId}").stdout().trim() == tipAfterA

        when: 'instance B continues the task to completion, driving the second round ("verify") and pushing it'
        def twoEnters = (System.lineSeparator() * 2)
        new GitResumeRunner(assembly(new ByteArrayInputStream(twoEnters.getBytes('UTF-8'))), worktreesB, 'taskId')
                .run(instanceB, taskId, pipeline(), RunArguments.InteractiveMode.ALL, false)

        then: 'instance B\'s own round commit for "verify" exists, distinct from instance A\'s "build" round'
        def verifyRoundSha = gitRunner.run(instanceB, 'log', "gnomish/${taskId}", '--format=%H', '--grep',
                '^gnomish: round verify#0$').stdout().trim()
        verifyRoundSha
        verifyRoundSha != tipAfterA

        and: 'a third, wholly independent clone confirms that round reached origin — proof this is not local-only (FR11 push scope is the round commit; the final Completed/cleanup commit is a separate, unpushed lifecycle write)'
        def verifyClone = freshClone('verify-clone')
        gitRunner.run(verifyClone, 'fetch', 'origin', "gnomish/${taskId}:refs/remotes/origin/gnomish/${taskId}")
        gitRunner.run(verifyClone, 'cat-file', '-e', verifyRoundSha).exitCode() == 0
    }
}
