package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.git.PendingVerification
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import spock.lang.Specification

/**
 * FR21, FR25 (design D15, D19) of add-sandbox-core and FR5, FR8, UX2 of add-git-workflow:
 * {@code gnomish run --sandbox --resume}. It answers the same five recorded outcomes the host path
 * does, with the same dialogs (UX2), but the interrupted-visit case has an extra sandbox-specific
 * decision: a snapshot commit that {@code state.json} never recorded is an interrupted
 * VERIFICATION, so the round is already complete on the branch and must not be salvaged over.
 *
 * <p>Driven through ports only (design D13(c) of split-into-modules): {@code SandboxRunSupport} and
 * its factory are interfaces, so no container is ever started.
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class ContainerResumeRoutingSpec extends Specification implements RunChainFakes {

    TaskBranchGit branches = Mock(TaskBranchGit)
    TaskRepository taskRepository = Mock(TaskRepository)
    SandboxRunSupport support = Mock(SandboxRunSupport)
    ScriptedExecutor executor = new ScriptedExecutor([completedRound()])

    TaskRecord record = freshRecord()
    TaskState recordedState = TaskState.atStageStart('build')
    Optional<PendingVerification> pending = Optional.empty()
    ScriptedConsoleIO console = new ScriptedConsoleIO([''])

    def setup() {
        branches.ensureLocalTaskBranch(_, _) >> true
        support.taskRepository() >> taskRepository
        support.persistence() >> new InMemoryAttemptPersistence()
        support.workspace() >> new FakeWorkspace()
        support.pieces(_) >> null
        support.readFinalState() >> TaskState.atStageStart('build')
        support.readTaskJson() >> { record }
        support.readStateOrInitial(_) >> { recordedState }
        support.pendingVerification() >> { pending }
    }

    private static TaskRecord recordWith(RecordedOutcome outcome, EscalationReport escalation = null) {
        new TaskRecord(new TaskContext('PROJ-1', 'title', 'body', List.<Decision> of()),
                'base-sha', NOW, outcome, escalation, false)
    }

    private String resume(boolean discardWork = false) {
        def runner = new ContainerResumeRunner(assemblyRunningLoop(executor, console),
                new TaskGit(Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit)),
                new SandboxProperties(null, null, null, null, null, null, false),
                new FactoryProperties(null, null, null, null, null), 'taskId', { _c, _t, _s, _sp, _fp, _cred ->
                    support
                } as ContainerSupportFactory)
        def originalOut = System.out
        def captured = new ByteArrayOutputStream()
        System.out = new PrintStream(captured, true, 'UTF-8')
        try {
            runner.run(CLONE_DIR, 'PROJ-1', completingPipeline(), [], RunArguments.InteractiveMode.NONE, discardWork)
        } finally {
            System.out = originalOut
        }
        captured.toString('UTF-8')
    }

    // FR8: there is nothing to resume without a task branch, and saying so up front is better than
    // materializing an environment around a task that does not exist.
    def "refuses to resume when no task branch exists anywhere"() {
        when:
        resume()

        then:
        1 * branches.harden(CLONE_DIR)
        1 * branches.ensureLocalTaskBranch(CLONE_DIR, 'PROJ-1') >> false

        and:
        def ex = thrown(UsageException)
        ex.message.contains('no task branch found for "PROJ-1"')

        and: 'no environment was touched'
        0 * support._
    }

    // FR8, FR21: the ordinary interrupted visit. The environment is reattached for the recorded
    // stage — a live box is needed both for the salvage and for verifying a pending snapshot — and
    // the leftovers are salvaged in-box before the run continues.
    def "reattaches for the recorded stage and salvages the leftovers, then drives the run"() {
        when:
        resume()

        then:
        1 * support.reattachFor('build')
        1 * support.salvageLeftovers('PROJ-1')
        0 * support.disposeExistingEnvironment()

        and:
        1 * support.completeAndDispose(_ as TaskState)
        executor.requests.size() == 1
    }

    // FR21, D15: a snapshot commit unrecorded in state.json is an interrupted VERIFICATION — the
    // round is already complete on the branch. The box is still reattached (the verification has to
    // run somewhere) but salvaging would commit over a finished round, so it is skipped.
    def "reattaches but does not salvage when a pending verification is recorded"() {
        given:
        pending = Optional.of(new PendingVerification('sha-1', 'build', 0))

        when:
        resume()

        then:
        1 * support.reattachFor('build')
        0 * support.salvageLeftovers(_)
    }

    // FR8: --discard-work throws the surviving environment away instead, so the next materialize
    // seeds a fresh clone at the recorded tip — no reattach, no salvage.
    def "disposes of the existing environment under --discard-work"() {
        when:
        resume(true)

        then:
        1 * support.disposeExistingEnvironment()
        0 * support.reattachFor(_)
        0 * support.salvageLeftovers(_)
    }

    // FR8: at the pipeline END there is no stage to reattach for, so neither reattach nor salvage
    // runs — the run goes straight to the terminal drive.
    def "neither reattaches nor salvages when the recorded position is the pipeline end"() {
        given:
        recordedState = new TaskState(new Position.PipelineEnd(), 0, [], ExecutorUsage.none())

        when:
        resume()

        then:
        0 * support.reattachFor(_)
        0 * support.salvageLeftovers(_)
    }

    // UX2: outcome `completed` prints the same final status summary as the host path and stops —
    // no environment, no engine round.
    def "reports a completed branch without starting an environment"() {
        given:
        record = recordWith(new RecordedOutcome.Completed())

        when:
        def output = resume()

        then:
        output.contains('PROJ-1')
        executor.requests.isEmpty()
        0 * support.sweepOrphans()
        0 * support.reattachFor(_)
    }

    // FR25, D19: outcome `escalated` re-opens the same dialog the host path uses, and a non-blank
    // answer is committed FACTORY-SIDE over bare objects — before any environment materializes — so
    // the in-box clone contains the decision from the start.
    def "commits an escalation answer factory-side before the environment materializes"() {
        given:
        def report = new EscalationReport.DecisionNeeded('which database?', ['postgres', 'sqlite'])
        record = recordWith(new RecordedOutcome.Escalated(report), report)
        console = new ScriptedConsoleIO(['use postgres'])

        when:
        resume()

        then:
        1 * taskRepository.appendDecision('PROJ-1', {
            it.body() == 'use postgres'
        })

        then: 'and only then does the environment come up'
        1 * support.sweepOrphans()
        executor.requests.size() == 1
    }

    // FR5: a BLANK answer resumes on the return alone — nothing is committed, so the decision
    // history stays truthful.
    def "commits nothing when the escalation answer is blank"() {
        given:
        def report = new EscalationReport.AttemptsExhausted(3)
        record = recordWith(new RecordedOutcome.Escalated(report), report)

        when:
        resume()

        then:
        0 * taskRepository.appendDecision(_, _)
        executor.requests.size() == 1
    }

    // FR5: `escalated` with no recorded report can only mean a corrupted branch — refused, not
    // guessed at, exactly as on the host path.
    def "refuses an escalated branch whose escalation report is missing"() {
        given:
        record = recordWith(new RecordedOutcome.Escalated(new EscalationReport.AttemptsExhausted(3)), null)

        when:
        resume()

        then:
        def ex = thrown(InternalErrorException)
        ex.message.contains('no lastEscalation recorded')
    }

    // UX2: outcome `paused` is the same manual-checkpoint confirmation as the host path — it states
    // which stage passed, waits for Enter, and appends no decision.
    def "confirms a manual checkpoint, appending no decision"() {
        given:
        record = recordWith(new RecordedOutcome.Paused('build'))

        when:
        resume()

        then:
        console.printed.any {
            it.contains("Stage 'build' passed") && it.contains('Manual checkpoint')
        }
        0 * taskRepository.appendDecision(_, _)
        executor.requests.size() == 1
    }

    // FR8: outcome `aborted` refuses, pointing at the KEPT task environment — the container twin of
    // the host path's "inspect the kept worktree".
    def "refuses to resume an aborted branch, pointing at the kept environment"() {
        given:
        record = recordWith(new RecordedOutcome.Aborted('build', 'persistence failed'))

        when:
        resume()

        then:
        def ex = thrown(UsageException)
        ex.message.contains('its last recorded outcome is Aborted')
        ex.message.contains('kept task environment')
        executor.requests.isEmpty()
    }
}
