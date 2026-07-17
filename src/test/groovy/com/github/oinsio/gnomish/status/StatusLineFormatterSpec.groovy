package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Verdict
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * StatusLineFormatter (package-private): the individual line-formatting
 * helpers StatusTextRenderer assembles the full report from. Covers each
 * optional-field branch of {@code decisionLine} directly, since the renderer
 * spec only ever exercises the "both present" case, and the exact millisecond
 * total {@code checkHighlight} reports via {@code totalMillis}.
 *
 * <p>Implements FR10, UX2, D7 of add-manual-run.
 */
class StatusLineFormatterSpec extends Specification {

    private static final Instant STARTED = Instant.parse('2026-07-16T14:35:10Z')

    def "decisionLine with neither author nor stage renders only the body"() {
        given:
        def decision = new Decision('patch in place', null, null, null)

        expect:
        StatusLineFormatter.decisionLine(decision) == 'patch in place'
    }

    def "decisionLine with an author but no stage appends only the author suffix"() {
        given:
        def decision = new Decision('patch in place', null, 'operator', STARTED)

        expect:
        StatusLineFormatter.decisionLine(decision) == 'patch in place (by operator)'
    }

    def "decisionLine with a stage but no author appends only the stage suffix"() {
        given:
        def decision = new Decision('patch in place', 'plan', null, STARTED)

        expect:
        StatusLineFormatter.decisionLine(decision) == 'patch in place [stage: plan]'
    }

    def "decisionLine with both author and stage appends both suffixes in order"() {
        given:
        def decision = new Decision('patch in place', 'plan', 'operator', STARTED)

        expect:
        StatusLineFormatter.decisionLine(decision) == 'patch in place (by operator) [stage: plan]'
    }

    def "checkHighlight reports the exact summed millisecond total across all checks"() {
        given:
        def checks = [
            new CheckResult(new CheckRef(0, 'builtin:files_exist'), new Verdict.Pass(), Duration.ofMillis(120)),
            new CheckResult(new CheckRef(1, 'command:./gradlew test'), new Verdict.Pass(), Duration.ofMillis(380))
        ]
        def record = new AttemptRecord(0, AttemptRecord.Result.PASSED, STARTED, checks, ExecutorUsage.none(), JudgeUsage.none())

        expect:
        StatusLineFormatter.checkHighlight(record) == '2 checks passed, 500ms'
    }

    def "checkHighlight reports exactly 'no checks' when the round ran no checks at all"() {
        given:
        def record = new AttemptRecord(0, AttemptRecord.Result.DECISION_NEEDED, STARTED, [], ExecutorUsage.none(), JudgeUsage.none())

        expect:
        StatusLineFormatter.checkHighlight(record) == 'no checks'
    }
}
