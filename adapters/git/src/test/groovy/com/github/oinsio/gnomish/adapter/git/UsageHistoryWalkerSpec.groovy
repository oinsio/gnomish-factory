package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.UsageFold
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14, NFR-C1 of add-git-workflow; FR14, FR19 of define-executor-contract: {@code gnomish usage}
 * reconstructs per-round usage by chronologically walking {@code state.json} git history — a row per
 * round record met for the first time, its stage and task read from the record, never from commit
 * messages or the position. Core round-detection scenarios; edge cases live in {@link
 * UsageHistoryWalkerEdgeCasesSpec}, the walk's range in {@link UsageHistoryRangeSpec}.
 */
class UsageHistoryWalkerSpec extends Specification implements UsageHistoryFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupUsageHistoryFixture()
    }

    // Scenario: cli/task-inspection / Failed rounds are visible cost
    def "FR14: a stage that fails round 1 then passes round 2 — both rounds appear with usage and count toward totals"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-1', UntrustedText.tracker('T'), UntrustedText.tracker('B'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))

        def failedRound = round('PROJ-1', 'implement', 0, AttemptRecord.Result.QUALITY_FAILURE, 1000, 100)
        def stateAfterFail = TaskState.atStageStart('implement').recordQualityFailure(failedRound, ExecutorVersions.none())
        persistRound('PROJ-1', stateAfterFail, 'implement', 0)

        def passedRound = round('PROJ-1', 'implement', 1, AttemptRecord.Result.PASSED, 2000, 200)
        def stateAfterPass = stateAfterFail.recordUnburnedRound(passedRound, ExecutorVersions.none())
        persistRound('PROJ-1', stateAfterPass, 'implement', 1)

        when:
        def result = walker.walk(cloneDir, 'PROJ-1')

        then:
        result instanceof UsageHistoryResult.Found
        def found = result as UsageHistoryResult.Found
        found.rows().size() == 2

        and: 'round 0 is the quality failure, round 1 is the pass — both visible as cost'
        found.rows()[0].attempt().stage() == 'implement'
        found.rows()[0].attempt().round() == 0
        found.rows()[0].attempt().result() == AttemptRecord.Result.QUALITY_FAILURE
        found.rows()[1].attempt().round() == 1
        found.rows()[1].attempt().result() == AttemptRecord.Result.PASSED

        and: 'both rounds are folded into the snapshot'
        found.recordedTotals() == UsageFold.foldAll(found.rows()*.attempt())
        found.recordedTotals().rounds() == 2
        found.recordedTotals().byRole()[ExecutorRole.TRANSFORM]['claude-x'].input() == 300L
    }

    // M5, FR14, FR19 of define-executor-contract: each passing commit already carries the ADVANCED
    // position, and every stage's first round replaces the attempt list — the shape the old walk read
    // as "the next stage's first round" and billed to the stage after the one that ran it. The row's
    // stage is the record's own.
    // Scenario: cli/task-inspection / A first-round pass is billed to its own stage
    // Scenario: pipeline/stage-engine / Round identity is written, never inferred
    def "FR19: three consecutive stages passing on their first round are billed to the three stages that ran"() {
        given: 'a task whose stages a, b and c each pass on their first round, advancing in the same commit'
        taskRepository().createTask(new TaskContext('PROJ-9', UntrustedText.tracker('T'), UntrustedText.tracker('B'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('a'))
        def state = TaskState.atStageStart('a')
        [
            ['a', 'b', 500L],
            ['b', 'c', 600L],
            ['c', null, 700L]
        ].each { String stage, String next, long wall ->
            def advanced = next == null ? new Position.PipelineEnd() : new Position.AtStage(next)
            state = state.recordPassAndAdvance(round('PROJ-9', stage, 0, AttemptRecord.Result.PASSED, wall, 10), advanced, ExecutorVersions.none())
            persistRound('PROJ-9', state, stage, 0)
            state = state.advanceTo(advanced)
        }

        when:
        def found = walker.walk(cloneDir, 'PROJ-9') as UsageHistoryResult.Found

        then: 'three rows, each billed to the stage its record names, in order'
        found.rows()*.attempt()*.stage() == ['a', 'b', 'c']
        found.rows()*.attempt()*.taskId() == ['PROJ-9', 'PROJ-9', 'PROJ-9']

        and: 'their usage is the usage each stage paid'
        found.rows()*.attempt()*.usage()*.getAt(0)*.wallTime() == [500, 600, 700].collect {
            Duration.ofMillis(it)
        }
    }

    // FR1 of make-checkpoint-gate-durable: a manual stage's first round passes into the gate, so its
    // commit's attempt list is a fresh one (not grown from the previous stage's) and the round is
    // attributed to the position it is recorded at — the gate, which names the stage that ran it.
    def "FR14: a manual stage's passing first round recorded at its gate is billed to that stage"() {
        given: 'implement passes into release'
        taskRepository().createTask(new TaskContext('PROJ-7', UntrustedText.tracker('T'), UntrustedText.tracker('B'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def afterImplement = TaskState.atStageStart('implement')
                .recordPassAndAdvance(round('PROJ-7', 'implement', 0, AttemptRecord.Result.PASSED, 500, 50), new Position.AtStage('release'), ExecutorVersions.none())
        persistRound('PROJ-7', afterImplement, 'implement', 0)

        and: 'release, a manual stage, passes its first round and holds the task at its gate'
        def atGate = afterImplement.advanceTo(new Position.AtStage('release'))
                .recordPassAndAdvance(round('PROJ-7', 'release', 0, AttemptRecord.Result.PASSED, 700, 70), new Position.AwaitingApproval('release'), ExecutorVersions.none())
        persistRound('PROJ-7', atGate, 'release', 0)

        when:
        def found = walker.walk(cloneDir, 'PROJ-7') as UsageHistoryResult.Found

        then:
        found.rows()*.attempt()*.stage() == ['implement', 'release']
    }

    // Scenario: git/git-task-persistence / History of past stages lives in git
    def "FR14: advancing to a new stage starts a fresh round history and still yields one row for the new stage's first round"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-2', UntrustedText.tracker('T'), UntrustedText.tracker('B'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))

        def implementRound = round('PROJ-2', 'implement', 0, AttemptRecord.Result.PASSED, 500, 50)
        def stateAtImplement = TaskState.atStageStart('implement').recordUnburnedRound(implementRound, ExecutorVersions.none())
        persistRound('PROJ-2', stateAtImplement, 'implement', 0)

        def verifyRound = round('PROJ-2', 'verify', 0, AttemptRecord.Result.PASSED, 700, 70)
        def stateAtVerify = stateAtImplement.advanceTo(new Position.AtStage('verify')).recordUnburnedRound(verifyRound, ExecutorVersions.none())
        persistRound('PROJ-2', stateAtVerify, 'verify', 0)

        when:
        def result = (walker.walk(cloneDir, 'PROJ-2') as UsageHistoryResult.Found)

        then:
        result.rows().size() == 2
        result.rows()*.attempt()*.stage() == ['implement', 'verify']
        result.rows()[1].attempt().round() == 0
    }

    def "FR14: branch absent everywhere is reported as not-found, not an exception"() {
        when:
        def result = walker.walk(cloneDir, 'NO-SUCH-TASK')

        then:
        noExceptionThrown()
        result instanceof UsageHistoryResult.NotFound
    }

    def "FR14: a task branch with only a task.json commit (no rounds yet) yields an empty history"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-4', UntrustedText.tracker('T'), UntrustedText.tracker('B'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))

        when:
        def result = (walker.walk(cloneDir, 'PROJ-4') as UsageHistoryResult.Found)

        then:
        result.rows().isEmpty()
        result.recordedTotals() == UsageSnapshot.EMPTY
    }

    // FR13 of tighten-spec-contract: the walk starts at the registered commit, so a branch the
    //     factory never registered records no round — even one whose tip carries round commits.
    def "FR13: a task branch with no factory register yields an empty history"() {
        given:
        persistRound('PROJ-8', TaskState.atStageStart('implement').recordUnburnedRound(round('PROJ-8', 'implement', 0, AttemptRecord.Result.PASSED, 500, 50), ExecutorVersions.none()))
        assert runner.run(cloneDir, 'update-ref', '-d', 'refs/gnomish-factory/PROJ-8').exitCode() == 0

        when:
        def result = walker.walk(cloneDir, 'PROJ-8')

        then:
        result instanceof UsageHistoryResult.Found
        (result as UsageHistoryResult.Found).rows().isEmpty()
    }
}
