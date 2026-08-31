package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.git.TaskWorktreePath
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, FR7, UX1 of add-git-workflow: {@code gnomish run --git} on a FRESH task. Four facts belong
 * to this runner and to nothing below it — run-start hygiene before anything is created, the
 * operator-facing banner naming where the work will live (printed BEFORE the pipeline runs, since
 * its whole purpose is to tell an operator where to look while it runs), the deterministic
 * branch/worktree names, and the terminal record-and-dispose.
 *
 * <p>Driven through ports only (design D13(c) of split-into-modules), over a real
 * {@code RunnerOutcomeLoop} and {@code Engine} on the domain's scripted engine-port fakes. Named
 * for the fresh run so it does not collide with the composition root's own {@code
 * GitModeRunnerSpec}, which drives the same class against a real git clone.
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class GitModeRunnerFreshRunSpec extends Specification implements RunChainFakes {

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot

    TaskLifecycleStore lifecycleStore = Mock(TaskLifecycleStore)
    TaskBranchGit branches = Mock(TaskBranchGit)
    TaskWorktreeGit worktrees = Mock(TaskWorktreeGit)
    TaskStoreGit store = Stub(TaskStoreGit)

    def setup() {
        cloneDir = tempDir.resolve('my-project')
        worktreesRoot = tempDir.resolve('worktrees')
        Files.createDirectories(cloneDir)
        Files.createDirectories(TaskWorktreePath.resolve(worktreesRoot, cloneDir, 'PROJ-1'))
        store.taskRepository(_, _) >> lifecycleStore
        store.attemptPersistence(_, _) >> { persistence }
        store.readRecordedState(_) >> TaskState.atStageStart('build')
    }

    private static TaskContext context(String taskId = 'PROJ-1') {
        new TaskContext(taskId, 'title', 'body', List.<Decision> of())
    }

    ScriptedExecutor executor = new ScriptedExecutor([completedRound()])

    /** Assignable, since setup() stubs the port once: the abort scenario swaps in a breaking one. */
    InMemoryAttemptPersistence persistence = new InMemoryAttemptPersistence()

    private GitModeRunner runner() {
        new GitModeRunner(assemblyRunningLoop(executor), new TaskGit(store, branches, worktrees), worktreesRoot)
    }

    private String runCapturingStdout(String base = null) {
        def originalOut = System.out
        def captured = new ByteArrayOutputStream()
        System.out = new PrintStream(captured, true, 'UTF-8')
        try {
            runner().run(cloneDir, base, completingPipeline(), context(), TaskState.atStageStart('build'),
                    RunArguments.InteractiveMode.NONE)
        } finally {
            System.out = originalOut
        }
        captured.toString('UTF-8')
    }

    // UX1 "the operator always knows where the work lives": both banner lines are printed, they
    // name the deterministic branch and worktree, and the branch line comes first.
    def "prints the branch and worktree banner naming where the work will live"() {
        when:
        def output = runCapturingStdout()

        then:
        def lines = output.readLines()
        def branchLine = lines.find { it.contains('git mode: branch') }
        def worktreeLine = lines.find { it.contains('git mode: worktree') }
        branchLine.contains('gnomish/PROJ-1')
        worktreeLine.contains(TaskWorktreePath.resolve(worktreesRoot, cloneDir, 'PROJ-1').toString())
        lines.indexOf(branchLine) <lines.indexOf(worktreeLine)
    }

    // FR6, FR7: the run-start hygiene, the single creation, and the terminal boundary. Hygiene runs
    // BEFORE the task is created — a stale worktree registration or a live hook left in place would
    // otherwise apply to the branch this run is about to make.
    def "prunes and hardens before creating the task, then records and disposes at the end"() {
        when:
        runCapturingStdout()

        then:
        1 * worktrees.pruneWorktrees(cloneDir)

        then:
        1 * branches.harden(cloneDir)

        then:
        1 * lifecycleStore.createTask({ it.taskId() == 'PROJ-1' }, 'HEAD', _)
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Completed)
        1 * worktrees.cleanUp(cloneDir, _, _ as TaskOutcome.Completed)

        and: 'the pipeline really ran against the worktree — the recorded outcome is not a no-op'
        executor.requests.size() == 1
    }

    // FR6, design D7: --base chooses where the task branch starts; absent, it is the clone's current
    // HEAD, passed through literally because the port requires a non-blank baseRef.
    def "passes the base ref through, defaulting an absent one to HEAD"() {
        when:
        runCapturingStdout(base)

        then:
        1 * lifecycleStore.createTask(_, expected, _)

        where:
        base || expected
        null || 'HEAD'
        'release/1.2' || 'release/1.2'
    }

    // FR6, FR8: an ABORTED run is still a terminal boundary — the outcome is recorded and the
    // worktree disposed of by the same pair as a completed one (which keeps it, for forensics)
    // — and only then is the abort re-thrown, so the exit code still reflects the failure.
    def "records and disposes on an aborted run before rethrowing"() {
        given: 'persistence that breaks on its first write, which is what aborts the engine'
        persistence = new InMemoryAttemptPersistence(failOnCall: 1)

        when:
        runCapturingStdout()

        then:
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Aborted)
        1 * worktrees.cleanUp(cloneDir, _, _ as TaskOutcome.Aborted)

        and:
        thrown(AbortedException)
    }
}
