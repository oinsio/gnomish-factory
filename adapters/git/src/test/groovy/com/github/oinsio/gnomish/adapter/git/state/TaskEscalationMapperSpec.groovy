package com.github.oinsio.gnomish.adapter.git.state

import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.MismatchCause
import com.github.oinsio.gnomish.status.StatusReportReferenceFixture
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The {@code task.json} escalation vocabulary round-trips (testing.md, wire-vocabulary rule): one
 * sample per {@link EscalationReport} kind — the status-report corpus's samples, so both wire
 * contracts are pinned over the same reports — written to its DTO, through JSON, and read back
 * equal; the sample set is checked against the sealed permits, never a hand-listed subset.
 *
 * <p>FR7 of define-executor-contract; FR3, FR4 of add-git-workflow.
 */
class TaskEscalationMapperSpec extends Specification {

    // FR7: a new kind without a sample fails here
    def "the samples cover every EscalationReport kind"() {
        expect:
        StatusReportReferenceFixture.referenceEscalations()*.class as Set == EscalationReport.permittedSubclasses as Set
    }

    // FR13 of define-executor-contract: a new mismatch cause without a sample fails here
    def "the samples cover every PipelineMismatch cause"() {
        expect:
        StatusReportReferenceFixture.referenceEscalations()
                .findAll { it instanceof EscalationReport.PipelineMismatch }
                .collect {
                    (it as EscalationReport.PipelineMismatch).cause().class
                } as Set == MismatchCause.permittedSubclasses as Set
    }

    // FR13 of define-executor-contract: a stale-stage report keeps its bytes; a report naming no cause fails closed
    def "a stale-stage mismatch omits the executor cause, and a mismatch naming neither cause is refused"() {
        given:
        def mapper = TaskStateJson.mapper()

        when:
        def json = mapper.writeValueAsString(TaskEscalationMapper.toDto(new EscalationReport.PipelineMismatch(
                        new MismatchCause.StaleStage(UntrustedText.branchDocument('gone')))))

        then:
        json.contains('"staleStage":"gone"')
        !json.contains('executorVersion')

        when:
        TaskEscalationMapper.fromDto(mapper.readValue('{"type":"pipelineMismatch"}', EscalationReportDto))

        then:
        def e = thrown(NullPointerException)
        e.message.contains('neither staleStage nor executorVersion')
    }

    // FR7: each kind survives domain → DTO → JSON → DTO → domain unchanged
    def "#report.class.simpleName round-trips through task.json"() {
        given:
        def mapper = TaskStateJson.mapper()

        when:
        def json = mapper.writeValueAsString(TaskEscalationMapper.toDto(report))
        def readBack = TaskEscalationMapper.fromDto(mapper.readValue(json, EscalationReportDto))

        then:
        readBack == report

        where:
        report << StatusReportReferenceFixture.referenceEscalations()
    }
}
