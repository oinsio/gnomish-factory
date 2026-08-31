package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14, NFR-C1 of add-git-workflow: {@code gnomish usage} reconstructs per-round usage by
 * chronologically walking {@code state.json} git history (design D14) — a row per new
 * {@link com.github.oinsio.gnomish.adapter.git.state.StateAttemptDto}, never by parsing commit
 * messages, so salvage/cleanup/task.json-only commits naturally yield no rows. Core round-detection
 * scenarios; edge cases live in {@link UsageHistoryWalkerEdgeCasesSpec}.
 */
class UsageHistoryWalkerSpec extends Specification implements UsageHistoryFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupUsageHistoryFixture()
    }

    def "FR14: a stage that fails round 1 then passes round 2 — both rounds appear with usage and count toward totals"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-1', 'T', 'B', []), null, TaskState.atStageStart('implement'))

        def failedRound = round(0, AttemptRecord.Result.QUALITY_FAILURE, 1000, 100)
        def stateAfterFail = TaskState.atStageStart('implement').recordQualityFailure(failedRound)
        persistRound('PROJ-1', stateAfterFail, 'implement', 0)

        def passedRound = round(1, AttemptRecord.Result.PASSED, 2000, 200)
        def stateAfterPass = stateAfterFail.recordUnburnedRound(passedRound)
        persistRound('PROJ-1', stateAfterPass, 'implement', 1)

        when:
        def result = walker.walk(cloneDir, 'PROJ-1')

        then:
        result instanceof UsageHistoryResult.Found
        def found = result as UsageHistoryResult.Found
        found.rows().size() == 2

        and: 'round 0 is the quality failure, round 1 is the pass — both visible as cost'
        found.rows()[0].stage() == 'implement'
        found.rows()[0].attempt().round() == 0
        found.rows()[0].attempt().result() == AttemptRecord.Result.QUALITY_FAILURE
        found.rows()[1].attempt().round() == 1
        found.rows()[1].attempt().result() == AttemptRecord.Result.PASSED

        and: 'both rounds are included in the totals'
        found.totals().wallMillis() == 3000L
        found.totals().tokensByModel()['claude-x'].input() == 300L
    }

    // FR4, FR14 of harden-task-branch-contract: the passing round's commit already carries the
    // ADVANCED position, so a walk that read the stage off that commit's position would bill the
    // round to the stage that follows it. The round is attributed by where it ran, not by where
    // its commit left the task.
    def "FR14: a passing round whose commit already advanced the position is billed to the stage that ran it"() {
        given: 'a task whose implement stage passes, advancing to verify in that same commit'
        taskRepository().createTask(new TaskContext('PROJ-9', 'T', 'B', []), null, TaskState.atStageStart('implement'))

        def implementRound = round(0, AttemptRecord.Result.PASSED, 500, 50)
        def afterPass = TaskState.atStageStart('implement')
                .recordPassAndAdvance(implementRound, new Position.AtStage('verify'))
        persistRound('PROJ-9', afterPass, 'implement', 0)

        and: 'the verify stage then records its own first round'
        def verifyRound = round(0, AttemptRecord.Result.PASSED, 700, 70)
        persistRound('PROJ-9', afterPass.advanceTo(new Position.AtStage('verify')).recordUnburnedRound(verifyRound),
                'verify', 0)

        when:
        def found = walker.walk(cloneDir, 'PROJ-9') as UsageHistoryResult.Found

        then: 'two rows, each billed to the stage that actually paid for it'
        found.rows().size() == 2
        found.rows()[0].stage() == 'implement'
        found.rows()[1].stage() == 'verify'

        and: 'their usage is unchanged by the attribution'
        found.rows()[0].executorUsage().wallTime() == Duration.ofMillis(500)
        found.rows()[1].executorUsage().wallTime() == Duration.ofMillis(700)
    }

    def "FR14: advancing to a new stage starts a fresh round history and still yields one row for the new stage's first round"() {
        given:
        taskRepository().createTask(new TaskContext('PROJ-2', 'T', 'B', []), null, TaskState.atStageStart('implement'))

        def implementRound = round(0, AttemptRecord.Result.PASSED, 500, 50)
        def stateAtImplement = TaskState.atStageStart('implement').recordUnburnedRound(implementRound)
        persistRound('PROJ-2', stateAtImplement, 'implement', 0)

        def verifyRound = round(0, AttemptRecord.Result.PASSED, 700, 70)
        def stateAtVerify = stateAtImplement.advanceTo(new Position.AtStage('verify')).recordUnburnedRound(verifyRound)
        persistRound('PROJ-2', stateAtVerify, 'verify', 0)

        when:
        def result = (walker.walk(cloneDir, 'PROJ-2') as UsageHistoryResult.Found)

        then:
        result.rows().size() == 2
        result.rows()[0].stage() == 'implement'
        result.rows()[1].stage() == 'verify'
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
        taskRepository().createTask(new TaskContext('PROJ-4', 'T', 'B', []), null, TaskState.atStageStart('implement'))

        when:
        def result = (walker.walk(cloneDir, 'PROJ-4') as UsageHistoryResult.Found)

        then:
        result.rows().isEmpty()
        result.totals().wallMillis() == null
    }
}
