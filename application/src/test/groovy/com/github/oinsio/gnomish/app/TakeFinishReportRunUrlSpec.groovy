package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * The tracker half of a green external check's audit trail (NFR-O2 of add-sandbox-core): a check
 * that passed with the platform's run URL in its verdict is listed in the finish report the take
 * publishes, so a reviewer reaches the authoritative run from the tracker. The adapter half — the
 * poll returning that URL — is {@code GithubWorkflowRunVerdictWireMockSpec}'s.
 *
 * <p>Implements NFR-O2 of add-sandbox-core.
 */
class TakeFinishReportRunUrlSpec extends Specification {

    static final TaskRef REF = new TaskRef('PROJ-1')
    static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    static final String RUN_URL = 'https://github.com/acme/widgets/actions/runs/7'

    Tracker tracker = Mock()

    // Scenario: verification/github-external-check / A green check is auditable from the tracker
    def "NFR-O2: the finish comment surfaces the run URL a passing external check carried"() {
        given: 'a task whose one round passed an external check carrying the run URL'
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest('external:ci')), new Verdict.Pass(RUN_URL),
                Duration.ofSeconds(3))
        def round = new AttemptRecord(0, AttemptRecord.Result.PASSED, Instant.EPOCH, [check], ExecutorUsage.none(),
        JudgeUsage.none(), [], Stop.none())
        def state = new TaskState(new Position.PipelineEnd(), 1, [round], ExecutorUsage.none())
        def context = new TaskContext('PROJ-1', UntrustedText.tracker('Fix the widget'), UntrustedText.tracker('body'),
                List.<Decision> of())
        tracker.fetchTask(REF) >> TrackerTaskFixtures.taskWith(REF, new TrackerTaskState.Working(INSTANCE.value()))
        String published = null

        when:
        TakeFinishReport.finish(new TaskOutcome.Completed(state), context, 'gnomish/PROJ-1',
                TrackerTaskFixtures.orderFor(REF, tracker, INSTANCE), VirtualTimeRetries.terminalWrite())

        then:
        1 * tracker.finish(REF, _ as String) >> { TaskRef ref, String summary ->
            published = summary
        }

        and:
        published.contains(RUN_URL)
    }
}
