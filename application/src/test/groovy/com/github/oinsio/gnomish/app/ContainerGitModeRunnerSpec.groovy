package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import spock.lang.Specification

/**
 * FR6, FR7, UX1 of add-git-workflow in container mode: {@code gnomish run --sandbox} on a FRESH
 * task. The container twin of {@code GitModeRunner} — hooks neutralized on the clone, the banner
 * naming the branch AND the environment (the operator's two handles on a sandboxed run), the task
 * created over BARE objects before any environment materializes, then the shared terminal drive.
 *
 * <p>Driven through ports only (design D13(c) of split-into-modules): {@code SandboxRunSupport} and
 * its factory are interfaces, so no container is ever started.
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class ContainerGitModeRunnerSpec extends Specification implements RunChainFakes {

    private static final TaskContext CONTEXT = new TaskContext('PROJ-1', 'title', 'body', List.<Decision> of())

    TaskBranchGit branches = Mock(TaskBranchGit)
    TaskRepository taskRepository = Mock(TaskRepository)
    SandboxRunSupport support = Mock(SandboxRunSupport)
    ScriptedExecutor executor = new ScriptedExecutor([completedRound()])

    def setup() {
        support.taskRepository() >> taskRepository
        support.persistence() >> new InMemoryAttemptPersistence()
        support.workspace() >> new FakeWorkspace()
        support.pieces(_) >> null
        support.readFinalState() >> TaskState.atStageStart('build')
    }

    private String run(String base = null) {
        def runner = new ContainerGitModeRunner(assemblyRunningLoop(executor),
                new TaskGit(Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit)),
                new SandboxProperties(null, null, null, null, null, null, false, null, null, null, null),
                new FactoryProperties(null, null, null, null, null), { _c, _t, _s, _sp, _fp, _def, _cred ->
                    support
                } as ContainerSupportFactory)
        def originalOut = System.out
        def captured = new ByteArrayOutputStream()
        System.out = new PrintStream(captured, true, 'UTF-8')
        try {
            runner.run(CLONE_DIR, base, completingPipeline(), [], CONTEXT, TaskState.atStageStart('build'),
            RunArguments.InteractiveMode.NONE)
        } finally {
            System.out = originalOut
        }
        captured.toString('UTF-8')
    }

    // UX1: a sandboxed run has TWO places the operator may need to look — the branch and the task
    // environment — so the banner names both, before anything materializes.
    def "hardens the clone and prints the branch and environment banner before running"() {
        when:
        def output = run()

        then:
        1 * branches.harden(CLONE_DIR)

        and:
        output.contains('container mode: branch gnomish/PROJ-1')
        output.contains('container mode: environment PROJ-1')
    }

    // FR6, FR7: the task is created over bare objects — before any environment exists — and the run
    // then goes through the shared terminal drive to a clean disposal.
    def "creates the task over bare objects, then drives the run to a clean disposal"() {
        when:
        run()

        then:
        1 * taskRepository.createTask({ it.taskId() == 'PROJ-1' }, 'HEAD')

        and:
        1 * support.sweepOrphans()
        1 * support.completeAndDispose(_ as TaskState)
        executor.requests.size() == 1
    }

    // FR6, design D7: --base is passed through; absent, the branch starts at the clone's HEAD.
    def "passes the base ref through, defaulting an absent one to HEAD"() {
        when:
        run(base)

        then:
        1 * taskRepository.createTask(_, expected)

        where:
        base || expected
        null || 'HEAD'
        'release/1.2' || 'release/1.2'
    }

    // FR7: on a FRESH run, a creation failure names an operator mistake — the same remap as the host
    // path, so the exit code and the guidance do not depend on which mode was used.
    def "remaps a creation failure into the same usage error the host path raises"() {
        given:
        taskRepository.createTask(_, _) >> {
            throw new GitTaskRepositoryException('PROJ-1', TaskLifecycleEvent.STARTED, 'branch exists', 'x')
        }

        when:
        run()

        then:
        def ex = thrown(UsageException)
        ex.message.startsWith('could not start git-mode task "PROJ-1"')
        ex.message.contains('--resume')

        and: 'and no environment was ever started'
        0 * support.sweepOrphans()
    }
}
