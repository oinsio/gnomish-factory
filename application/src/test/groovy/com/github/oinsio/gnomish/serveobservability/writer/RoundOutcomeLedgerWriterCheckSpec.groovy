package com.github.oinsio.gnomish.serveobservability.writer

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckOutcome
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.EngineEvent
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.RoundExecutor
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.serveobservability.InstanceInfo
import com.github.oinsio.gnomish.serveobservability.json.LedgerJson
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link RoundOutcomeLedgerWriter} for an executor check (NFR-O1, UX3 of
 * migrate-verify-to-executor-contract): the check's finish event writes one {@code roundOutcome}
 * line, role {@code verdict}, naming the executor, the status its rounds ended on, the check's index
 * and — for a check this instance resumed — the key it took up from the pending record. Every
 * non-round event writes nothing. The same lines over the engine's own events are {@link
 * RecordedExecutorNameSpec}'s.
 */
class RoundOutcomeLedgerWriterCheckSpec extends Specification implements RotatingLedgerAppenderFixture {

    static final Instant NOW = Instant.parse('2026-11-13T09:00:00Z')
    static final AttemptKey KEY = new AttemptKey('TASK-9', 'build', 1)

    @TempDir
    Path homeDir

    private RoundOutcomeLedgerWriter writer() {
        new RoundOutcomeLedgerWriter(ledgerAppenderFor(homeDir, NOW), new InstanceInfo('gnome-1', 'host1', '1.0.0'),
                new VirtualClock(NOW))
    }

    private List<Map> ledgerLines() {
        def file = ledgerFileFor(homeDir, NOW)
        (Files.exists(file) ? Files.readAllLines(file) : []).collect {
            LedgerJson.mapper().readValue(it, Map)
        }
    }

    static final RoundExecutor SCM_STATUS = new RoundExecutor('scm-status', UntrustedText.factory('built-in'))

    private static EngineEvent.CheckFinished finished(int index, CheckOutcome outcome) {
        new EngineEvent.CheckFinished(KEY, new CheckResult(new CheckRef(index, UntrustedText.manifest('external:scm-status:ci')),
                new Verdict.Pass(null, []), Duration.ZERO), outcome)
    }

    // Scenario: verification/pending-poll / Kill after persist resumes the poll
    // UX3: the resuming instance's line shows the resume as `resumed key=<prefix>`
    def "a resumed check's line names the executor, the verdict role, its status, its index and the resumed key"() {
        when:
        writer().onEvent(finished(1, new CheckOutcome(SCM_STATUS, new ExecutionResult.Completed(),
                UntrustedText.branchDocument('sonar-AY12'))))

        then:
        ledgerLines() == [
            [
                version : 1,
                type : 'roundOutcome',
                instance : [instanceId: 'gnome-1', host: 'host1', factoryVersion: '1.0.0'],
                at : '2026-11-13T09:00:00Z',
                taskId : 'TASK-9',
                stage : 'build',
                attempt : 1,
                executor : 'scm-status@built-in',
                role : 'verdict',
                status : 'completed',
                check : 1,
                resumedKey: 'sonar-AY12',
                pendingKey: null
            ]
        ]
    }

    // NFR-O1: the status is the one the check's rounds ended on, in the round line's words
    def "a check that ended #why reads status=#token and no resumed key"() {
        when:
        writer().onEvent(finished(0, new CheckOutcome(new RoundExecutor('http', UntrustedText.factory('built-in')), status, null)))

        then:
        ledgerLines().collect { [it.status, it.resumedKey] } == [[token, null]]

        where:
        why | status || token
        'completed' | new ExecutionResult.Completed() || 'completed'
        'with a quality failure' | failed(ExecutionResult.FailureClass.QUALITY) || 'failed/quality'
        'with no verdict' | failed(ExecutionResult.FailureClass.INFRASTRUCTURE) || 'failed/infrastructure'
        'at a bound' | failed(ExecutionResult.FailureClass.LIMIT) || 'failed/limit'
    }

    // NFR-O2: only the round event and an executor check's finish write a line
    def "no other engine event writes a line, a check finish carrying no outcome included"() {
        when:
        writer().onEvent(new EngineEvent.AttemptStarted(KEY))
        writer().onEvent(new EngineEvent.RunStarted('TASK-9', new Position.AtStage('build'), 0))
        writer().onEvent(new EngineEvent.CheckStarted(KEY, new CheckRef(0, UntrustedText.manifest('judge:criteria.md'))))
        writer().onEvent(finished(0, null))

        then:
        ledgerLines().isEmpty()
    }

    private static ExecutionResult.Failed failed(ExecutionResult.FailureClass failureClass) {
        new ExecutionResult.Failed(failureClass, UntrustedText.factory('why'), [], null)
    }
}
