package com.github.oinsio.gnomish.executorcontract

import static com.github.oinsio.gnomish.executorcontract.WireJson.t

import spock.lang.Specification

/**
 * FR3 of define-executor-contract: a verdict's pass is derived from its result levels against the
 * check's threshold, never declared — it passes when no result reaches the threshold, and the
 * results below it stay on the verdict as advisory findings.
 */
class VerdictThresholdSpec extends Specification {

    private static Answer.Verdict verdict(List<SarifLevel> levels) {
        new Answer.Verdict([
            new SarifRun(t('a'), []),
            new SarifRun(t('b'), levels.collect {
                new SarifResult(null, it, t('finding'), null, null)
            })
        ])
    }

    def "FR3: results at #levels against threshold #threshold #verb"() {
        expect:
        threshold.passes(verdict(levels)) == passes

        where:
        levels | threshold || passes
        [] | SarifLevel.NONE || true
        [
            SarifLevel.WARNING,
            SarifLevel.NOTE
        ] | SarifLevel.ERROR || true
        [
            SarifLevel.WARNING,
            SarifLevel.ERROR
        ] | SarifLevel.ERROR || false
        [SarifLevel.NOTE] | SarifLevel.WARNING || true
        [
            SarifLevel.NOTE,
            SarifLevel.WARNING
        ] | SarifLevel.WARNING || false
        [SarifLevel.NONE] | SarifLevel.NOTE || true
        [SarifLevel.NONE] | SarifLevel.NONE || false

        verb = passes ? 'passes' : 'fails'
    }

    def "FR3: a warning-only verdict passes an error threshold with its findings kept as advisory"() {
        given:
        def read = WireJson.answer(WireJson.readTree(
                        '{"kind":"verdict","sarif":{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"judge"}},' +
                        '"results":[{"ruleId":"javadoc","message":{"text":"Foo has no javadoc"}}]}]}}'))

        expect:
        read instanceof AnswerReading.Parsed
        def verdict = (Answer.Verdict) ((AnswerReading.Parsed) read).answer()
        SarifLevel.ERROR.passes(verdict)
        !SarifLevel.WARNING.passes(verdict)
        verdict.runs()*.results().flatten()*.message() == [t('Foo has no javadoc')]
    }
}
