package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.gitobjects.GitObjects
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The host take funnel's describe preflight (FR4, FR13, UX1 of define-executor-contract; design D5,
 * as revised 2026-10-31): {@link TakeEngineExecution} describes the law on the host, over the
 * worktree the rounds run in, before it assembles anything, and a law it cannot describe parks the
 * task as an invalid law does — no assembly, so no round and no attempt — while a described law is
 * the one the run assembles with.
 */
class TakeEngineExecutionDescribeSpec extends Specification implements RunChainFakes {

    @TempDir
    Path worktree

    Tracker tracker = Mock(Tracker)
    TaskState state = TaskState.atStageStart('build')
    LawBinding binding = LawBinding.atRevision(CLONE_DIR, GitObjects.HEAD)
    List mediums = []
    List assembledWith = []

    def "FR4, UX1: a law the preflight refuses parks the task INFRA on the host, before anything is assembled"() {
        given:
        def error = new ConfigError('config.yaml', 'executors.acme.program',
                "executor 'acme' could not be described (external failure): the environment refused to start the program")

        when:
        def result = execution(new LawDescription.Refused([error])).run(order(), bootstrap(), bootstrap().context(), state)

        then:
        mediums == [
            new DescribeMedium.Host(worktree)
        ]
        assembledWith.isEmpty()
        1 * tracker.park(REF, ParkReason.INFRA, {
            it.contains("executor 'acme' could not be described (external failure)")
        })
        0 * tracker.recordAbort(*_)
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).finalState() == state
    }

    def "FR13: a described law is the one the host run assembles with"() {
        given:
        def described = new DescribedLaw(binding, [:])

        when:
        execution(described).run(order(), bootstrap(), bootstrap().context(), state)

        then:
        thrown(UnsupportedOperationException)
        assembledWith == [described]
    }

    private TakeEngineExecution execution(LawDescription answer) {
        def store = Stub(TaskStoreGit) {
            taskRepository(_) >> Stub(TaskLifecycleStore)
            attemptPersistence(_, _) >> new InMemoryAttemptPersistence()
        }
        def git = new TaskGit(store, Stub(TaskBranchGit), Mock(TaskWorktreeGit), new ClaimEpochBook())
        def self = null
        self = [
            describe: { order, medium, credentials, lawBinding ->
                mediums << medium
                answer
            },
            workspaceAt: { Path root -> null },
            withHostGitPush: { decoration -> self },
            assemble: { order, context, initial, persistence, credentials, law ->
                assembledWith << law
                // The engine is not this spec's subject: stop here, once the law was handed on.
                throw new UnsupportedOperationException('assembled')
            },
        ] as RunAssembly
        new TakeEngineExecution(self, git, CLONE, dispatch(), [], new ClaimLossFlag(), binding)
    }

    private TakeOutcomeDispatch dispatch() {
        new TakeOutcomeDispatch(VirtualTimeRetries.terminalWrite(), new AbortFuse(new AbortHandler(tracker, FIXED_CLOCK), 3))
    }

    private TakeOrder order() {
        takeOrder(heldByUs(), tracker, runOrder(completingPipeline()))
    }

    private ResumeBootstrap bootstrap() {
        new ResumeBootstrap('PROJ-1', CheckpointMechanicsFixtures.context(), null, null, worktree,
                new BranchAdoption.AlreadyLocal('PROJ-1'), 'abc123', false, BasePin.UNPINNED)
    }
}
