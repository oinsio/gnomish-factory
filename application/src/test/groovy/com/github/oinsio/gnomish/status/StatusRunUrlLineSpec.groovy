package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * The run URL a passing external check carried, as the text report shows it (NFR-O2 of
 * add-sandbox-core; finding F109 of tighten-spec-contract): one line per check that has one, on
 * both planes, the URL neutralized by the plane's own exit for uncarried untrusted text.
 *
 * <p>Implements NFR-O2 of add-sandbox-core.
 */
class StatusRunUrlLineSpec extends Specification {

    private static final String RUN_URL = 'https://github.com/acme/widgets/actions/runs/7'

    private static CheckResult check(String label, Verdict verdict) {
        new CheckResult(new CheckRef(0, UntrustedText.manifest(label)), verdict, Duration.ofMillis(5))
    }

    private static AttemptRecord round(List<CheckResult> checks) {
        new AttemptRecord(0, AttemptRecord.Result.PASSED, Instant.EPOCH, checks, ExecutorUsage.none(),
                JudgeUsage.none(), [], Stop.none())
    }

    def "NFR-O2: only a passing check that carried a run URL gets a line, on either plane"() {
        given: 'a round with a URL-carrying pass, a pass without a URL, and a failure'
        def record = round([
            check('builtin:files_exist', new Verdict.Pass()),
            check('external:ci', new Verdict.Pass(RUN_URL)),
            check('command:test', new Verdict.Fail([
                new Finding('red', null, null)
            ]))
        ])

        expect:
        StatusLineFormatter.runUrlLines(record, plane) == [
            'external:ci passed: ' + RUN_URL
        ]

        where:
        plane << ReportPlane.values()
    }

    def "NFR-O2: a round whose checks carried no run URL renders no run line"() {
        expect:
        StatusLineFormatter.runUrlLines(round([
            check('builtin:files_exist', new Verdict.Pass())
        ]), ReportPlane.COMMENT).isEmpty()
    }

    def "NFR-O2: a hostile run URL is neutralized like a finding's text on each plane"() {
        given: 'a URL crafted with a line break, a terminal escape, a mention and an issue reference'
        def esc = new String(Character.toChars(0x1B))
        def record = round([
            check('external:ci', new Verdict.Pass("https://ci.example/@team/#123${esc}[31m\nfake: row"))
        ])

        expect:
        StatusLineFormatter.runUrlLines(record, plane) == [line]

        where:
        plane | line
        ReportPlane.CONSOLE | 'external:ci passed: https://ci.example/@team/#123\\nfake: row'
        ReportPlane.COMMENT | 'external:ci passed: https://ci.example/@​team/#​123\\nfake: row'
    }

    def "NFR-O2: the full report lists the run line under its round's summary line"() {
        given:
        def report = new StatusReport('PROJ-1', UntrustedText.tracker('Fix'), UntrustedText.tracker('body'),
                new Position.PipelineEnd(), 1, [
                    round([
                        check('external:ci', new Verdict.Pass(RUN_URL))
                    ])
                ], [], null,
                ExecutorUsage.none(), null, null)

        expect:
        new StatusTextRenderer(ReportPlane.COMMENT).renderFull(report).contains(
                '  Round 0: passed (1 checks passed, 5ms)\n    external:ci passed: ' + RUN_URL + '\nTotals:')
    }
}
