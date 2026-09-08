package com.github.oinsio.gnomish.app

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
import com.github.oinsio.gnomish.e2e.gitea.GiteaTaskSeedFixture
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.AutoCleanup
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR11, G2 of add-git-workflow (task 6.6): proves the best-effort push machinery ({@link
 * com.github.oinsio.gnomish.adapter.git.BestEffortPush}, wired inside {@code
 * GitAttemptPersistence}) actually lands round commits on a real HTTP-auth remote — not just a
 * local bare repo, which every other git-mode spec uses. Reuses {@link GiteaContainerFixture}
 * exactly as bootstrapped by task 6.5's harness spec.
 *
 * <p>Verification is deliberately independent of the local clone: after the run, a completely
 * fresh {@code git clone} of the Gitea repo is made and its branch tip is compared against the
 * local worktree's committed history, so the assertion would fail if the push silently no-op'd
 * (e.g. missing {@code origin}) or used the wrong refspec.
 *
 * <p>Implements FR11 of add-git-workflow.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unreachable — see GiteaAvailability; Docker is a dev/CI prerequisite for the Gitea E2E layer (.claude/rules/testing.md)')
class GiteaBestEffortPushE2ESpec extends Specification implements GiteaTaskSeedFixture, AppAssemblyFixture {

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture()

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot

    // Wired per feature, so it gets its own repository — see GiteaContainerFixture's sharing rule.
    String originUrl

    def setupSpec() {
        gitea.start()
    }

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'push-project')
        originUrl = gitea.createRepository("best-effort-push-${System.nanoTime()}")
        addRemote(cloneDir, 'origin', originUrl)
        seedAndPushGnomishTask(cloneDir)
        worktreesRoot = tempDir.resolve('worktrees-root')
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

    private GitModeRunner newRunner() {
        def assembly = newAssembly(new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8')))
        new GitModeRunner(assembly, TaskGitFixture.real(), worktreesRoot)
    }

    private static TaskContext context(String taskId) {
        new TaskContext(taskId, 'title', 'body', List.<Decision> of())
    }

    // FR11: after a git-mode round commits, the round commit itself (design D11's push scope is
    // "after every round" — not the later Completed/cleanup lifecycle commits GitOutcomeRecorder
    // writes separately, with no push of its own) is pushed over the real Gitea HTTP-auth remote —
    // verified via a completely fresh clone from Gitea, independent of any state the local
    // worktree/clone might report.
    def "a completed git-mode run pushes the round commit to the Gitea origin over HTTP auth"() {
        given:
        def taskId = 'PUSH-1'

        when:
        newRunner().run(cloneDir, null, pipeline(), context(taskId), TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.ALL)

        then: 'the run reached completion locally, and the round commit it made is identifiable by its fixed message'
        def roundSha = roundCommitSha(cloneDir, taskId, 'build')
        roundSha

        and: 'a fresh independent clone from Gitea, fetching just the task branch, already has that round commit'
        def freshClone = tempDir.resolve('fresh-verify-clone')
        gitExitCode(tempDir, 'clone', originUrl, freshClone.toString()) == 0
        roundReachedOrigin(freshClone, taskId, roundSha)

        and: 'the pushed tree really carries the round content, not just an empty ref'
        def tree = gitOutput(freshClone, 'ls-tree', '-r', '--name-only', roundSha)
        tree.contains('.gnomish-task/task.json')
    }
}
