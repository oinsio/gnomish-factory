package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import spock.lang.Specification

/**
 * FR11, NFR-R2 of add-sandbox-core: the shared terminal drive every container-mode path ends in.
 * Its whole job is the DISPOSAL DECISION — the box is disposed of only on a clean completion, and
 * kept stopped on any other exit, because a task environment that outlives a crashed run is what
 * makes salvage and resume possible while a live gnome process in a disposed box is not
 * recoverable at all.
 *
 * <p>Driven through ports only (design D13(c) of split-into-modules): {@code SandboxRunSupport} is
 * an interface, so this spec observes the disposal decision directly rather than through Docker.
 * Named for the disposal decision so it does not collide with the composition root's own {@code
 * ContainerTerminalDriveSpec}, which drives the same class over real container plumbing.
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class ContainerTerminalDriveDisposalSpec extends Specification implements RunChainFakes {

    private static final TaskContext CONTEXT = new TaskContext('PROJ-1', 'title', 'body', List.<Decision> of())

    SandboxRunSupport support = Mock(SandboxRunSupport)
    ScriptedExecutor executor = new ScriptedExecutor([completedRound()])
    InMemoryAttemptPersistence persistence = new InMemoryAttemptPersistence()

    def setup() {
        support.persistence() >> { persistence }
        support.workspace() >> new FakeWorkspace()
        support.pieces(_) >> null
        support.readFinalState() >> TaskState.atStageStart('build')
    }

    private void drive(ScriptedConsoleIO io = new ScriptedConsoleIO(['']), Verdict verdict = new Verdict.Pass()) {
        ContainerTerminalDrive.run(assemblyRunningLoop(executor, io, verdict), support, completingPipeline(),
                CONTEXT, TaskState.atStageStart('build'), RunArguments.InteractiveMode.NONE, CLONE_DIR, null)
    }

    // FR11, NFR-R2: runner start sweeps environments a dead instance left labelled, BEFORE the run
    // materializes its own — so a reattaching resume is never swept by the run it belongs to.
    def "sweeps orphaned environments before the run starts, then disposes on completion"() {
        when:
        drive()

        then:
        1 * support.sweepOrphans()

        then: 'the run happened, and a clean completion disposes of the box'
        1 * support.completeAndDispose(_ as TaskState)
        0 * support.keepStopped()
        0 * support.recordAborted(_)
        executor.requests.size() == 1
    }

    // FR5 of fix-denial-report-attachment: the denial source outlives the process that created it,
    // so the run hands its environments the position its last committed attempt recorded — BEFORE
    // any environment materializes, since the first read of the first round is what it delimits.
    // Without it that read replays every denial the surviving source still holds onto this round.
    def "restores the committed denial cursor before the run materializes anything"() {
        when:
        drive()

        then:
        1 * support.restoreDenialCursor()

        then: 'only afterwards does the run assemble and drive the engine'
        1 * support.completeAndDispose(_ as TaskState)
        executor.requests.size() == 1
    }

    // NFR-R2: an ABORTED run records the outcome and keeps the box stopped — never disposes. A
    // disposed box would take the evidence and the salvageable work with it.
    def "records the abort and keeps the box stopped, never disposing"() {
        given:
        persistence = new InMemoryAttemptPersistence(failOnCall: 1)

        when:
        drive()

        then:
        1 * support.recordAborted(_ as TaskOutcome.Aborted)
        1 * support.keepStopped()
        0 * support.completeAndDispose(_)

        and:
        thrown(AbortedException)
    }
}
