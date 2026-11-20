package com.github.oinsio.gnomish.adapter.agent.program

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.VerdictDerivation
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.executorcontract.ExecutorResult
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import spock.lang.Specification

/**
 * FR6 of migrate-verify-to-executor-contract (design D23): the run URL a verdict answer's SARIF run
 * carries crosses the one contract → domain mapping, {@link ProgramResultMapping}, onto the engine's
 * {@code VerdictAnswer.Sarif}, and {@link VerdictDerivation} records it with a pass — the first run,
 * in the order written, that carries one.
 */
class ProgramResultRunUrlSpec extends Specification {

    private static final AttemptKey KEY = new AttemptKey('TASK-7', 'verify', 0)

    private static Map run(String runUrl, List<Map> results = []) {
        def run = [tool: [driver: [name: 'ci']], results: results]
        runUrl == null ? run : run + [properties: [runUrl: runUrl]]
    }

    private static VerdictAnswer.Sarif mapped(List<Map> runs) {
        def answer = new ObjectMapper().valueToTree([kind: 'verdict', sarif: [version: '2.1.0', runs: runs]])
        def result = new ExecutorResult(Status.COMPLETED, null, null, null, answer, Usage.absent(), [])
        def engine = ProgramResultMapping.toEngine('ci-relay', Role.VERDICT, KEY, result, [])
        (engine.answer() as ExecutionResult.VerdictReply).answer() as VerdictAnswer.Sarif
    }

    // Scenario: executor/wire-contract / A completed answer carries its run URL
    def "FR6: a verdict answer whose run carries a URL and no result at the threshold records that URL with the pass"() {
        when:
        def answer = mapped([
            run('https://ci/runs/7', [
                [level: 'note', message: [text: 'fyi']]
            ])
        ])

        then:
        answer.runUrl() == 'https://ci/runs/7'
        def pass = VerdictDerivation.derive(answer, Threshold.DEFAULT) as Verdict.Pass
        pass.runUrl() == 'https://ci/runs/7'
    }

    // Scenario: executor/wire-contract / The first run's URL is recorded
    def "FR6: of two runs that each carry a URL, the pass records the URL of the run written first"() {
        when:
        def answer = mapped([
            run('https://ci/runs/1'),
            run('https://ci/runs/2')
        ])

        then:
        (VerdictDerivation.derive(answer, Threshold.DEFAULT) as Verdict.Pass).runUrl() == 'https://ci/runs/1'
    }

    def "FR6: a first run without a URL leaves the URL to the first run, in the order written, that carries one"() {
        expect:
        mapped([
            run(null),
            run('  '),
            run('https://ci/runs/3'),
            run('https://ci/runs/4')
        ]).runUrl() == 'https://ci/runs/3'
    }

    def "FR6: an answer no run of which carries a URL passes with none"() {
        when:
        def answer = mapped([run(null), run(null)])

        then:
        answer.runUrl() == null
        (VerdictDerivation.derive(answer, Threshold.DEFAULT) as Verdict.Pass).runUrl() == null
    }
}
