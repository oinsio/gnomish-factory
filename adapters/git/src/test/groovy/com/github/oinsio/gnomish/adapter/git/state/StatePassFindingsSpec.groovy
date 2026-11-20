package com.github.oinsio.gnomish.adapter.git.state

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * A pass keeps its below-threshold findings across {@code state.json}: the writer fills the
 * existing {@code findings} field for {@code "pass"} as it does for {@code "fail"}, and the reader
 * reads them back, in order (FR7 of migrate-verify-to-executor-contract, design D5 as amended).
 * That the read-back findings feed no later attempt is pinned in {@code :domain} by
 * {@code PassFindingsFeedbackSpec}.
 */
class StatePassFindingsSpec extends Specification {

    private static final Instant STARTED_AT = Instant.parse('2026-11-08T09:00:00Z')

    // Scenario: verification/verdict-executors / A pass keeps its findings across the state file
    def "FR7: a pass with two warnings survives the state file with both findings in order"() {
        given:
        def pass = new Verdict.Pass('https://ci.example/runs/3', [
            new Finding('w1', 'src/A.java:1', null),
            new Finding('w2', null, 'consider a guard')
        ])
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest('builtin:lint')), pass, Duration.ofMillis(4))
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, STARTED_AT, [check], ExecutorUsage.none(), [], Stop.none())
        def state = new TaskState(new Position.AtStage('build'), 0, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())
        def mapper = TaskStateJson.mapper()

        when:
        def json = mapper.writeValueAsString(StateJsonMapper.toDto(state))
        def rebuilt = StateJsonMapper.fromDto(mapper.readValue(json, StateJsonDto))

        then: 'the document carries the findings under the pass'
        def written = mapper.readTree(json).get('attempts').get(0).get('checks').get(0)
        written.get('verdict').asText() == 'pass'
        written.get('findings')*.get('message')*.asText() == ['w1', 'w2']

        and: 'the read verdict is the same pass with the same two findings in the same order'
        rebuilt.attempts()[0].checkResults()[0].verdict() == pass
    }

    // FR7: a pass with nothing below the threshold still writes and reads the empty list
    def "FR7: a pass without findings reads back as a pass without findings"() {
        given:
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest('builtin:lint')),
                new Verdict.Pass(null, []), Duration.ZERO)
        def record = AttemptRecords.of(0, AttemptRecord.Result.PASSED, STARTED_AT, [check], ExecutorUsage.none(), [], Stop.none())
        def state = new TaskState(new Position.AtStage('build'), 0, [record], UsageSnapshot.EMPTY, ExecutorVersions.none())

        expect:
        StateJsonMapper.fromDto(StateJsonMapper.toDto(state)).attempts()[0].checkResults()[0].verdict() ==
                new Verdict.Pass(null, [])
    }
}
