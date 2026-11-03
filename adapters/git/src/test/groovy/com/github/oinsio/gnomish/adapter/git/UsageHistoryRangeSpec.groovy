package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR19, M5 of define-executor-contract (design D13): the usage walk reads only the task's own
 * history — the first-parent line from its recorded {@code baseCommit} (exclusive) to its register —
 * and rejects any round record naming another task, with one warning per record naming the commit.
 * Each feature reproduces one of the defects of 2026-10-09 on a real clone (adversarial posture).
 */
class UsageHistoryRangeSpec extends Specification implements UsageHistoryFixture {

    private static final String STATE = '.gnomish-task/state.json'

    @TempDir
    Path tempDir

    def setup() {
        setupUsageHistoryFixture()
    }

    // Scenario: cli/task-inspection / The base's envelope is not this task's history
    def "FR19: a base whose tree carried another task's envelope contributes no row and no warning"() {
        given: 'the base already carries the envelope of OTHER-1, with two recorded rounds'
        def other = otherTaskStates()
        commitOnBase(other.last(), 'carry another task envelope', true)

        and: 'PROJ-1 starts from that base and records one round'
        startTask('PROJ-1')
        persistRound('PROJ-1', TaskState.atStageStart('implement')
                .recordUnburnedRound(round('PROJ-1', 'implement', 0, AttemptRecord.Result.PASSED, 500, 50), ExecutorVersions.none()), 'implement', 0)

        when:
        UsageHistoryResult result = null
        def events = LogCaptureSupport.capture(UsageRoundRows, Level.INFO) {
            result = walker.walk(cloneDir, 'PROJ-1')
        }

        then: "only PROJ-1's round is a row"
        def found = result as UsageHistoryResult.Found
        found.rows()*.attempt()*.taskId() == ['PROJ-1']
        found.recordedTotals().rounds() == 1

        and: 'the range kept the base out, so the content check had nothing to reject'
        events.findAll { it.level == Level.WARN }.isEmpty()
    }

    // Scenario: cli/task-inspection / A merged-in foreign record is rejected
    def "FR19: a merge of the base into the branch is walked on the first-parent line, its foreign records rejected"() {
        given: 'PROJ-2 starts from the base and records one round'
        def other = otherTaskStates()
        startTask('PROJ-2')
        persistRound('PROJ-2', TaskState.atStageStart('implement')
                .recordUnburnedRound(round('PROJ-2', 'implement', 0, AttemptRecord.Result.PASSED, 500, 50), ExecutorVersions.none()), 'implement', 0)

        and: "the base then gains two commits carrying OTHER-1's state, one round more each"
        def baseSide = [
            commitOnBase(other[0], 'other round 0', false),
            commitOnBase(other[1], 'other round 1', false)
        ]

        and: "the base is merged into PROJ-2, the merge keeping the base's state.json, and registered"
        def merge = mergeBaseInto('PROJ-2', other[1])
        assert runner.run(cloneDir, 'update-ref', 'refs/gnomish-factory/PROJ-2', merge).exitCode() == 0

        when:
        UsageHistoryResult result = null
        def events = LogCaptureSupport.capture(UsageRoundRows, Level.INFO) {
            result = walker.walk(cloneDir, 'PROJ-2')
        }

        then: "only PROJ-2's round is a row"
        (result as UsageHistoryResult.Found).rows()*.attempt()*.taskId() == ['PROJ-2']

        and: 'one warning per foreign record, each naming the merge — never a commit of the merged-in side'
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 2
        warnings.every {
            it.formattedMessage.startsWith(OperatorEvent.USAGE_HISTORY_FOREIGN_RECORD.head())
        }
        warnings.every {
            it.formattedMessage.contains(merge) && it.formattedMessage.contains('OTHER-1')
        }
        baseSide.every { commit ->
            warnings.every {
                !it.formattedMessage.contains(commit)
            }
        }
    }

    // FR13 of tighten-spec-contract: a round the gnome wrote into state.json above the register
    //     is not a round of the report.
    def "FR13: a state.json round committed above the register is not reported"() {
        given:
        def first = TaskState.atStageStart('implement').recordQualityFailure(round('PROJ-9', 'implement', 0, AttemptRecord.Result.QUALITY_FAILURE, 500, 50), ExecutorVersions.none())
        persistRound('PROJ-9', first)
        def worktree = worktreeFor('PROJ-9')
        def forged = new GitAttemptPersistence(runner, worktree, 'PROJ-9', ClaimEpochSource.NONE)
        def registered = registeredId(cloneDir, 'PROJ-9')
        forged.persist('PROJ-9', first.recordUnburnedRound(round('PROJ-9', 'implement', 1, AttemptRecord.Result.PASSED, 900, 90), ExecutorVersions.none()),
                new ToolTrace(new AttemptKey('PROJ-9', 'implement', 1), []))
        assert runner.run(cloneDir, 'update-ref', 'refs/gnomish-factory/PROJ-9', registered).exitCode() == 0

        when:
        def result = walker.walk(cloneDir, 'PROJ-9') as UsageHistoryResult.Found

        then: 'the round above the register is not billed'
        result.rows().size() == 1
        result.rows()[0].attempt().round() == 0
    }

    /** OTHER-1's state.json after its first and after its second round, as its branch recorded them. */
    private List<String> otherTaskStates() {
        startTask('OTHER-1')
        def first = TaskState.atStageStart('implement')
                .recordQualityFailure(round('OTHER-1', 'implement', 0, AttemptRecord.Result.QUALITY_FAILURE, 100, 10), ExecutorVersions.none())
        persistRound('OTHER-1', first, 'implement', 0)
        def afterFirst = show('gnomish/OTHER-1', STATE)
        persistRound('OTHER-1', first.recordUnburnedRound(
                        round('OTHER-1', 'implement', 1, AttemptRecord.Result.PASSED, 200, 20), ExecutorVersions.none()), 'implement', 1)
        [
            afterFirst,
            show('gnomish/OTHER-1', STATE)
        ]
    }

    private void startTask(String taskId) {
        taskRepository().createTask(new TaskContext(taskId, UntrustedText.tracker('T'), UntrustedText.tracker('B'), []),
        TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
    }

    /** Commits {@code state} (and, when asked, OTHER-1's task.json) on the base; returns the commit. */
    private String commitOnBase(String state, String subject, boolean withTaskJson) {
        def dir = cloneDir.resolve('.gnomish-task')
        dir.toFile().mkdirs()
        dir.resolve('state.json').toFile().text = state
        if (withTaskJson) {
            dir.resolve('task.json').toFile().text = show('gnomish/OTHER-1', '.gnomish-task/task.json')
        }
        assert runner.run(cloneDir, 'add', '.gnomish-task').exitCode() == 0
        assert runner.run(cloneDir, 'commit', '-m', subject).exitCode() == 0
        show('HEAD')
    }

    /** Merges the base into the task's branch, resolving state.json to {@code state}; returns the merge. */
    private String mergeBaseInto(String taskId, String state) {
        def worktree = worktreeFor(taskId)
        runner.run(worktree, 'merge', '--no-ff', '--no-commit', show('HEAD'))
        worktree.resolve(STATE).toFile().text = state
        assert runner.run(worktree, 'add', STATE).exitCode() == 0
        assert runner.run(worktree, 'commit', '-m', 'merge the base').exitCode() == 0
        runner.run(worktree, 'rev-parse', 'HEAD').stdout().forParsing().trim()
    }

    private String show(String revision, String path) {
        runner.run(cloneDir, 'show', "${revision}:${path}").stdout().forParsing()
    }

    private String show(String revision) {
        runner.run(cloneDir, 'rev-parse', revision).stdout().forParsing().trim()
    }
}
