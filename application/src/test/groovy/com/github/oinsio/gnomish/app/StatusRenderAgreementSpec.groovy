package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.SeededCloneFixture
import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.git.BranchStateResult
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.status.StatusTextRenderer
import com.github.oinsio.gnomish.status.json.StatusReportJsonMapper
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR11 of add-manual-run; FR13 of add-git-workflow: the two renders of {@code gnomish status
 * <task>} are two views of one {@code StatusReport}. Both commands read the same real branch tip;
 * each printed document is compared with the render of the one report the branch reader builds
 * from that tip, so a render that consulted anything else — a second read, a live field — would
 * print something the report does not hold.
 */
class StatusRenderAgreementSpec extends Specification implements SeededCloneFixture {

    private static final String TASK = 'AGREE-1'

    @TempDir
    Path tempDir

    def setup() {
        setupSeededClone()
    }

    private List<String> printed(String... flags) {
        def console = new ScriptedConsoleIO()
        new StatusCommand(TaskGitFixture.realClaimless(), RegisteredCloneFixture.scope(registeredClone), console)
                .run(new DefaultApplicationArguments([
                    'status',
                    '--dir=' + cloneDir,
                    TASK,
                    *flags
                ] as String[]))
        console.printed
    }

    // Scenario: observability/status-report / Text and JSON agree
    def "FR11: the text and the JSON of one tip are both renders of the report built from it"() {
        given: 'a tip carrying a failed round, so both renders have something to say beyond the header'
        persistRound(TASK, TaskState.atStageStart('implement').recordQualityFailure(round(0, AttemptRecord.Result.QUALITY_FAILURE, 900, 40)))

        and: 'the one report the branch reader builds from that tip'
        def report = (TaskGitFixture.realClaimless().branches().readState(cloneDir, TASK) as BranchStateResult.Found).report()

        when:
        def text = printed()
        def json = printed('--json')

        then: 'the text is the text render of that report, the worktree line after it'
        text.first() == new StatusTextRenderer().renderFull(report) + ConsoleIO.LINE_END

        and: 'the JSON is the JSON render of the same report'
        json.first() == new StatusReportJsonMapper().serialize(report) + ConsoleIO.LINE_END

        and: 'and the two agree on what they both show'
        text.first().contains('Stage: implement')
        json.first().contains('"stage" : "implement"')
    }
}
