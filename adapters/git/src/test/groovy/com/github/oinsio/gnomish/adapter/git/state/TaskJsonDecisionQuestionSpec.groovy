package com.github.oinsio.gnomish.adapter.git.state

import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * The {@code decisions} entries of {@code task.json} carry the question and options a decision
 * answers (design D4 as amended 2026-11-21, D8 row "The question a decision answers" of
 * extract-agent-round-runner): the writer keeps their bytes, the reader mints them back as the
 * branch's text, and an entry written before the question existed reads with none and is written
 * back unchanged.
 *
 * <p>FR9, NFR-R2 of extract-agent-round-runner.
 */
class TaskJsonDecisionQuestionSpec extends Specification {

    private static final Instant AT = Instant.parse('2026-11-21T10:00:00Z')
    private static final String HOSTILE = 'Refactor \u001B[2K or patch?'

    private static final EscalationReport.DecisionNeeded ASKED = new EscalationReport.DecisionNeeded(
    UntrustedText.agent(HOSTILE),
    [
        UntrustedText.agent('refactor'),
        UntrustedText.agent('patch')
    ])

    private static TaskContext contextWith(Decision decision) {
        new TaskContext('task-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), [decision])
    }

    private static TaskJsonDto write(TaskContext context) {
        TaskJsonMapper.toDto(context, 'abc123', AT, null, null, false, BasePin.UNPINNED)
    }

    private static TaskJsonDto wire(TaskJsonDto dto) {
        TaskJsonMapper.readDto(UntrustedText.branchDocument(TaskStateJson.mapper().writeValueAsString(dto)))
    }

    // FR9: the decision entry carries the question and options byte for byte; the reader re-mints them
    def "a decision's question and options survive task.json as the branch's text"() {
        given:
        def decision = new Decision('refactor', 'build', 'operator', AT, ASKED)

        when:
        def dto = wire(write(contextWith(decision)))
        def read = TaskJsonMapper.fromDto(dto).context().decisions().first()

        then: 'the wire keeps the bytes'
        dto.decisions().first().question() == HOSTILE
        dto.decisions().first().options() == ['refactor', 'patch']

        and: 'the reader gives back the same decision, its text minted as the branch document'
        read == decision
        read.answers().question().provenance() == Provenance.BRANCH_DOCUMENT
        read.answers().options()*.provenance() == [
            Provenance.BRANCH_DOCUMENT,
            Provenance.BRANCH_DOCUMENT
        ]
    }

    // FR9: an open question (no options) round-trips with an empty option list
    def "an open question round-trips with no options"() {
        given:
        def open = new EscalationReport.DecisionNeeded(UntrustedText.agent('Which?'), [])
        def decision = new Decision('either', 'build', 'tracker', AT, open)

        expect:
        TaskJsonMapper.fromDto(wire(write(contextWith(decision)))).context().decisions() == [decision]
    }

    // FR9, NFR-R2: an entry written before the question reads with none, and the writer adds nothing
    // Scenario: git/git-task-persistence / Decisions written before the question read as before
    def "a decision entry with only body, stage, author and time reads with no question and is written back unchanged"() {
        given: 'a task.json whose decision carries the four fields of the old contract'
        def entry = '{"body":"patch in place","author":"operator","stage":"plan","at":"2026-07-16T14:21:30Z"}'
        def json = """{"version":1,"taskId":"task-1","title":"t","body":"b","createdAt":"2026-07-18T09:00:00Z",
                     "baseCommit":"abc123","decisions":[${entry}],"outcome":null,"lastEscalation":null}"""

        when:
        def record = TaskJsonMapper.fromDto(TaskJsonMapper.readDto(UntrustedText.branchDocument(json)))
        def decision = record.context().decisions().first()
        def rewritten = TaskJsonMapper.toDto(record.context(), 'abc123', AT, null, null, false, BasePin.UNPINNED)

        then: 'the decision reads as before, answering nothing'
        decision == new Decision('patch in place', 'plan', 'operator', Instant.parse('2026-07-16T14:21:30Z'))
        decision.answers() == null

        and: 'its entry is written back byte for byte'
        TaskStateJson.mapper().writeValueAsString(rewritten.decisions().first()) == entry
    }
}
