package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.port.check.SarifResultReading
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.VerdictDerivation
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.SarifLevel
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.charset.StandardCharsets
import java.time.Duration
import spock.lang.Specification

/**
 * FR5, FR7 of migrate-verify-to-executor-contract (design D5 as amended): a {@code shell} check's
 * findings file is read on exit 0 too, so a file holding more results than one verdict may carry
 * ({@link Answer.Verdict#MAX_RESULTS}) is capped, never dropped: the answer keeps the most severe
 * {@code MAX_RESULTS - 1} results in the order written and one result standing for the rest at the
 * most severe level among them, so the verdict derived under every threshold is the one the whole
 * file states. Dropping the file would make a green-exiting linter with 1001 errors pass.
 */
class ShellFindingsCapSpec extends Specification {

    private static final String PATH = '/scratch/findings.json'

    private static final int CAP = Answer.Verdict.MAX_RESULTS

    private static TaskExecutionEnvironment holding(String content) {
        def bytes = content.getBytes(StandardCharsets.UTF_8)
        [readFile: { String path, long cap ->
                assert path == PATH
                Optional.of(Arrays.copyOf(bytes, (int) Math.min(cap, bytes.length)))
            }] as TaskExecutionEnvironment
    }

    private static ShellOutcome.Answered judgedOnExitZero(String content) {
        def outcome = new CommandProcessRunner.CommandOutcome(0, 'tail', new ExecHandle.Wait.Exited(Duration.ZERO))
        def judged = ShellOutcome.judge('lint', outcome, holding(content), PATH)
        assert judged instanceof ShellOutcome.Answered
        judged as ShellOutcome.Answered
    }

    private static Verdict derived(ShellOutcome.Answered answered, Threshold threshold) {
        VerdictDerivation.derive(new VerdictAnswer.Sarif(answered.results().collect {
            SarifResultReading.toDomain(it.asSarif())
        }, null), threshold)
    }

    private static String legacy(int count) {
        '{"findings":[' + (1..count).collect {
            '{"message":"e' + it + '"}'
        }.join(',') + ']}'
    }

    private static String sarif(List<String> levels) {
        def results = levels.withIndex().collect { String level, int i ->
            '{"level":"' + level + '","message":{"text":"r' + i + '"}}'
        }
        '{"version":"2.1.0","runs":[{"tool":{"driver":{"name":"lint"}},"results":[' + results.join(',') + ']}]}'
    }

    def "FR5: an exit-0 check whose legacy file holds #count errors fails with the first #listed and one result for the rest"() {
        when:
        def answered = judgedOnExitZero(legacy(count))

        then: 'the answer fits the cap, and the check fails'
        answered.results().size() == CAP
        derived(answered, Threshold.ERROR) instanceof Verdict.Fail

        and: 'the first results in the order written, the last standing for the unlisted rest as an error'
        answered.results().take(listed)*.sarif()*.message()*.forLog() == (1..listed).collect {
            'e' + it
        }
        def rest = answered.results().last().sarif()
        rest.level() == SarifLevel.ERROR
        rest.message().forLog() == ResultCap.moreResults(count - listed)

        where:
        count << [CAP + 1, 5 * CAP]
        listed = CAP - 1
    }

    def "FR5: a legacy file at exactly the cap is read whole"() {
        expect:
        judgedOnExitZero(legacy(CAP)).results()*.sarif()*.message()*.forLog() == (1..CAP).collect {
            'e' + it
        }
    }

    def "FR5: an exit-0 SARIF file over the cap keeps its most severe results in the order written"() {
        given: 'notes everywhere, two errors near the end and one warning at the very end'
        def levels = (1..(CAP + 10)).collect { 'note' }
        levels[CAP + 3] = 'error'
        levels[CAP + 5] = 'error'
        levels[CAP + 9] = 'warning'

        when:
        def answered = judgedOnExitZero(sarif(levels))

        then: 'the two errors and the warning survive the cap, and the check fails'
        answered.results().size() == CAP
        def kept = answered.results().take(CAP - 1)*.sarif()
        kept.findAll {
            it.level() == SarifLevel.ERROR
        }*.message()*.forLog() == ["r${CAP + 3}", "r${CAP + 5}"]
        kept.count { it.level() == SarifLevel.WARNING } == 1
        kept.last().level() == SarifLevel.WARNING
        derived(answered, Threshold.ERROR) instanceof Verdict.Fail

        and: 'the rest are notes, so the result standing for them is a note'
        answered.results().last().sarif().level() == SarifLevel.NOTE
        answered.results().last().sarif().message().forLog() == ResultCap.moreResults(11)
    }

    def "FR7: a SARIF file over the cap derives the verdict the whole file states under every threshold"() {
        when: 'only warnings, more than the cap'
        def answered = judgedOnExitZero(sarif((1..(CAP + 1)).collect {
            'warning'
        }))

        then: 'they still pass under the default threshold and still fail under a warning threshold'
        derived(answered, Threshold.ERROR) instanceof Verdict.Pass
        derived(answered, Threshold.WARNING) instanceof Verdict.Fail
        answered.results().last().sarif().level() == SarifLevel.WARNING
    }
}
