package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
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
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR21, FR25 (design D15, D19) of add-sandbox-core and FR5, FR8, UX2 of add-git-workflow:
 * {@code gnomish run --sandbox --resume}. It answers the same five recorded outcomes the host path
 * does, through the same {@code EscalationResume} and with no prompt (UX2; FR3, FR4, FR5, FR9 of
 * make-run-headless), but the interrupted-visit case has an extra sandbox-specific decision: a
 * snapshot commit that {@code state.json} never recorded is an interrupted VERIFICATION, so the
 * round is already complete on the branch and must not be salvaged over — and both resume arms that
 * continue dispose the kept box first, since its clone is behind the park commit.
 *
 * <p>Driven through ports only (design D13(c) of split-into-modules): {@code SandboxRunSupport} and
 * its factory are interfaces, so no container is ever started. The console is never read (FR6).
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
    /** FR6 of make-run-headless: a console a resume may print to but never read from. */
    ScriptedConsoleIO console = new ScriptedConsoleIO() {
        @Override
        String readLine() {
            throw new AssertionError('a headless resume read the console')
        }
    }

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

    /** Every law binding the resumed chain assembled with, in order (FR12 of add-base-ref-resolution). */
    List lawBindings = []

    /** Resumes PROJ-1 with the given {@code --decision} ({@code null} for none). */
    private String resume(String decision = null, boolean discardWork = false) {
        def runner = new ContainerResumeRunner(
                assemblyRunningLoop(executor, console, new Verdict.Pass(), [], lawBindings),
                new TaskGit(Stub(TaskStoreGit), branches, Stub(TaskWorktreeGit), new ClaimEpochBook()),
                'taskId', { _c, _t, _s, _def, _cred ->
                    support
                } as ContainerSupportFactory)
        def originalOut = System.out
        def captured = new ByteArrayOutputStream()
        System.out = new PrintStream(captured, true, 'UTF-8')
        try {
            runner.run(
                    new RunOrder(CLONE_DIR, null, completingPipeline(), discardWork),
                    'PROJ-1', decision, [])
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

    // FR3 of fix-lifecycle-push: resume start is a touchpoint — after the branch is reconciled
    // INTO the clone, origin is reconciled up to it, delivering a push an earlier instance lost.
    def "reconciles the remote at resume start, right after the local branch is ensured"() {
        when:
        resume()

        then:
        1 * branches.ensureLocalTaskBranch(CLONE_DIR, 'PROJ-1') >> true

        then:
        1 * branches.reconcileRemote(CLONE_DIR, 'PROJ-1', 'resume-start')
    }

    // FR7, FR12, D13 of add-base-ref-resolution: the container resume binds its law exactly as the
    // host one does — from the LOCAL tip of the branch's pinned ref, never the clone's checkout.
    def "binds the resumed law from the task's pinned base ref, not from the clone's checkout"() {
        given:
        record = recordPinnedTo('release/1.18')

        when:
        resume()

        then:
        1 * branches.ensureLocalTaskBranch(CLONE_DIR, 'PROJ-1') >> true

        and:
        lawBindings == [
            new LawBinding.AtRevision(CLONE_DIR, 'refs/heads/release/1.18')
        ]
    }

    // FR8, FR21; FR18 of make-checkpoint-gate-durable: the ordinary interrupted visit. The shared
    // preparation (ContainerResumePreparation, whose own spec covers its branches) reattaches the
    // box for the recorded stage and salvages the leftovers in it — before the run is driven.
    def "prepares the box — reattach, then salvage — before it drives the run"() {
        when:
        resume()

        then:
        1 * support.reattachFor('build')
        0 * support.disposeExistingEnvironment()

        then:
        1 * support.salvageLeftovers('PROJ-1')

        then:
        1 * support.completeAndDispose(_ as TaskState)
        executor.requests.size() == 1
    }

    // FR8: --discard-work throws the surviving environment away instead, so the next materialize
    // seeds a fresh clone at the recorded tip — no reattach, no salvage.
    def "disposes of the existing environment under --discard-work"() {
        when:
        resume(null, true)

        then:
        1 * support.disposeExistingEnvironment()
        0 * support.reattachFor(_)
        0 * support.salvageLeftovers(_)
    }

    // FR1, FR4, FR11 of make-checkpoint-gate-durable: a tip held at a gate takes the checkpoint arm
    // whatever the outcome says, as on the host path — the kept box is disposed rather than
    // reattached and salvaged, then the approval lands factory-side as one commit and the engine
    // continues from the approved state (the pipeline end of a one-stage pipeline). The
    // reattach-for-the-gate's-stage rule lives on in the take path (TakeContainerResumeRoutingSpec).
    def "approves a tip at a gate in one commit after disposing the kept box when its outcome is #label"() {
        given:
        record = recorded == null ? freshRecord() : recordWith(recorded, report)
        recordedState = new TaskState(new Position.AwaitingApproval('build'), 0, [], ExecutorUsage.none())

        when:
        resume()

        then: 'the kept box goes first: its clone cannot learn of the approval'
        1 * support.disposeExistingEnvironment()

        then: 'one approval commit: the tip\'s gate and the state past it'
        1 * taskRepository.approveCheckpoint('PROJ-1', new Position.AwaitingApproval('build'), {
            it.position() == new Position.PipelineEnd()
        })
        0 * taskRepository.resumeFrom(_, _)
        0 * taskRepository.appendDecision(_, _, _)

        and: 'no reattach, no salvage, no re-park'
        0 * support.reattachFor(_)
        0 * support.salvageLeftovers(_)
        0 * support.recordPark(_, _)
        executor.requests.isEmpty()

        where:
        label | recorded | report
        'not recorded (park lost)' | null | null
        'paused (park landed)' | new RecordedOutcome.Paused('build') | null
        'a stale escalation' | new RecordedOutcome.Escalated(new EscalationReport.AttemptsExhausted(3)) | new EscalationReport.AttemptsExhausted(3)
    }

    // UX2: outcome `completed` prints the same final status summary as the host path and stops —
    // no environment, no engine round.
    def "reports a completed branch without starting an environment"() {
        given:
        record = recordWith(new RecordedOutcome.Completed())

        when:
        resume()

        then: 'the summary reached the operator through the run\'s own console owner'
        console.printed.join('').contains('PROJ-1')
        executor.requests.isEmpty()
        0 * support.sweepOrphans()
        0 * support.reattachFor(_)
    }

    // FR25, D19; FR3 of make-run-headless: outcome `escalated` with --decision is committed
    // FACTORY-SIDE over bare objects — before any environment materializes — so the in-box clone
    // contains the decision from the start; the kept box goes first, since its clone is behind.
    def "commits a --decision factory-side after disposing the kept box and before the environment materializes"() {
        given:
        def report = new EscalationReport.DecisionNeeded(UntrustedText.agent('which database?'), [
            UntrustedText.agent('postgres'),
            UntrustedText.agent('sqlite')
        ])
        record = recordWith(new RecordedOutcome.Escalated(report), report)

        when:
        resume('use postgres')

        then: 'the kept box that carried the park goes first: its clone cannot learn of the commits that follow'
        1 * support.disposeExistingEnvironment()

        then:
        1 * taskRepository.appendDecision('PROJ-1', {
            it.body() == 'use postgres' && it.author() == 'operator' && it.stage() == 'build'
        }, {
            it.attemptsUsed() == 0
        })

        then: 'and only then does the environment come up'
        1 * support.sweepOrphans()
        executor.requests.size() == 1
    }

    // FR4 of make-run-headless: no --decision over an AttemptsExhausted park appends no decision, so
    // the decision history stays truthful; FR7 of make-checkpoint-gate-durable: the reset lands as
    // the one resumed commit, factory-side, after the kept box is disposed and before any round.
    def "lands the resumed commit, appending no decision, when an escalated task is resumed without --decision"() {
        given:
        def report = new EscalationReport.AttemptsExhausted(3)
        record = recordWith(new RecordedOutcome.Escalated(report), report)
        recordedState = new TaskState(new Position.AtStage('build'), 2, [], ExecutorUsage.none())

        when:
        resume()

        then:
        1 * support.disposeExistingEnvironment()

        then:
        1 * taskRepository.resumeFrom('PROJ-1', {
            it.attemptsUsed() == 0 && it.position() == new Position.AtStage('build')
        })
        0 * taskRepository.appendDecision(_, _, _)

        then:
        1 * support.sweepOrphans()
        executor.requests.size() == 1
    }

    // FR4 of make-run-headless: a DecisionNeeded resumed without --decision is refused — the
    // question restated, nothing committed, no box touched, no environment started.
    def "restates a DecisionNeeded resumed without --decision, touching no box and writing nothing"() {
        given:
        def report = new EscalationReport.DecisionNeeded(UntrustedText.agent('which database?'), [
            UntrustedText.agent('postgres')
        ])
        record = recordWith(new RecordedOutcome.Escalated(report), report)

        when:
        resume()

        then:
        def refused = thrown(DecisionRequiredException)
        refused.stopRecord().contains('--resume=PROJ-1')
        console.printed.any {
            it.contains('which database?') && it.contains(CLONE_DIR.toString())
        }

        and:
        executor.requests.isEmpty()
        0 * taskRepository._
        0 * support.disposeExistingEnvironment()
        0 * support.sweepOrphans()
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

    // UX2; FR5 of make-run-headless; FR7 of make-checkpoint-gate-durable: a legacy `paused` outcome
    // recorded past its stage holds no gate, so it is consumed by the resumed commit — state kept as
    // recorded — and continues as the host path does: no checkpoint line, no prompt, no decision.
    def "consumes a legacy pause recorded past its stage with the resumed commit, disposing the kept box first"() {
        given:
        record = recordWith(new RecordedOutcome.Paused('build'))

        when:
        resume()

        then: 'the kept box\'s clone is behind the park commit: it goes before the factory-side commit'
        1 * support.disposeExistingEnvironment()

        then:
        1 * taskRepository.resumeFrom('PROJ-1', TaskState.atStageStart('build'))
        0 * taskRepository.approveCheckpoint(_, _, _)
        0 * taskRepository.appendDecision(_, _, _)

        and:
        !console.printed.any {
            it.contains(TerminalOutcomeRender.checkpointLine('build'))
        }
        executor.requests.size() == 1
    }

    // FR9 of make-run-headless: a --decision over any recorded outcome but `escalated` is a usage
    // error naming the conflict — raised after task.json is read, before any commit or any box.
    def "refuses a --decision over a task whose recorded outcome is #label, touching nothing"() {
        given:
        record = recorded == null ? freshRecord() : recordWith(recorded, recorded instanceof RecordedOutcome.Escalated ? recorded.report() : null)
        if (gate) {
            recordedState = new TaskState(new Position.AwaitingApproval('build'), 0, [], ExecutorUsage.none())
        }

        when:
        resume('nobody asked')

        then:
        def ex = thrown(UsageException)
        ex.message.contains('--decision')
        ex.message.contains('PROJ-1')
        ex.message.contains(named)

        and:
        executor.requests.isEmpty()
        0 * taskRepository._
        0 * support.disposeExistingEnvironment()
        0 * support.reattachFor(_)
        0 * support.sweepOrphans()

        where: 'FR4 of make-checkpoint-gate-durable: a gate refuses whatever its outcome says'
        label | recorded | gate | named
        'paused' | new RecordedOutcome.Paused('build') | false | "paused at a manual checkpoint after stage 'build'"
        'completed' | new RecordedOutcome.Completed() | false | 'completed'
        'not recorded' | null | false | 'interrupted run with no recorded outcome'
        'paused at a gate' | new RecordedOutcome.Paused('build') | true | "awaiting approval after stage 'build'"
        'escalated at a gate' | new RecordedOutcome.Escalated(new EscalationReport.AttemptsExhausted(3)) | true | "awaiting approval after stage 'build'"
    }

    // FR8: outcome `aborted` refuses, pointing at the KEPT task environment — the container twin of
    // the host path's "inspect the kept worktree".
    def "refuses to resume an aborted branch, pointing at the kept environment"() {
        given:
        record = recordWith(new RecordedOutcome.Aborted('build', UntrustedText.branchDocument('persistence failed')))

        when:
        resume()

        then:
        def ex = thrown(UsageException)
        ex.message.contains('its last recorded outcome is Aborted')
        ex.message.contains('kept task environment')
        executor.requests.isEmpty()
    }
}
