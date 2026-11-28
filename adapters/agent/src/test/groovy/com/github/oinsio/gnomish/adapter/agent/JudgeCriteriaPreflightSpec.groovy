package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import spock.lang.Specification

/**
 * FR13, NFR-R1, D8 of add-agent-executor; FR19, D14 of add-sandbox-core; FR4 of
 * extract-agent-round-runner: {@link JudgeCriteriaPreflight} runs ahead of a judge check's request —
 * an acceptance-criteria file the frozen {@link PipelineLaw} could not read is the request's answer,
 * {@code failed/infrastructure} naming the file, "before any process starts, never a criteria-less
 * vote", and is never thrown. Criteria come from the frozen law, not the working copy; the engine
 * writes the one operator line for the cannot-verify it derives to, so the preflight logs nothing.
 */
class JudgeCriteriaPreflightSpec extends Specification {

    def "a criteria file frozen readable in the law yields no refusal"() {
        given:
        def law = PipelineLaw.ofContent(['criteria.md': 'The output must be idempotent.'])

        expect:
        JudgeCriteriaPreflight.refusal(law, 'criteria.md').isEmpty()
    }

    def "a criteria file the frozen law could not read is a failed/infrastructure refusal naming the file"() {
        given:
        def law = PipelineLaw.ofContent([:])
        def logs = LogCaptureSupport.attach(JudgeCriteriaPreflight)

        when:
        def result = JudgeCriteriaPreflight.refusal(law, 'missing-criteria.md')

        then:
        def refusal = result.get()
        refusal.status() == Status.FAILED
        refusal.failureClass() == FailureClass.INFRASTRUCTURE
        refusal.reason().forLog() == "the judge's acceptance criteria file 'missing-criteria.md' is unreadable"
        refusal.details().forLog().startsWith('law file could not be read: missing-criteria.md')
        refusal.answer() == null
        refusal.usage() == Usage.absent()
        refusal.denials() == []

        and: 'nothing is logged here: the engine writes the one line'
        logs.list.isEmpty()

        cleanup:
        logs?.detach()
    }
}
