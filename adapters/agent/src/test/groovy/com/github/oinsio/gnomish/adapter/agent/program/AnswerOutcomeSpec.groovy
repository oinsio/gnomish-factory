package com.github.oinsio.gnomish.adapter.agent.program

import com.github.oinsio.gnomish.app.port.agent.AnswerFileReading
import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The one mapping of an answer file's reading onto a round's result (FR21, NFR-R5 of
 * extract-agent-round-runner; design D15, part 4), which the live agent round, a program's read and
 * the recovery of a snapshot all call.
 */
class AnswerOutcomeSpec extends Specification {

    static final Usage SPENT = new Usage(UsageProvenance.ABSENT, 7L, [:], [])

    static final AnswerFileReading.Refused REFUSED =
    new AnswerFileReading.Refused('wrote a malformed answer file: oops', UntrustedText.agent('oops'))

    def "FR21: a refused reading is failed/infrastructure naming #subject, the executor, the cause and the stderr tail"() {
        when:
        def result = AnswerOutcome.refused(subject, 'x', REFUSED, UntrustedText.subprocess('boom'))

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "${noun} 'x': wrote a malformed answer file: oops; its stderr ended with: boom".toString()
        result.answer() == null

        where:
        subject || noun
        ProgramFailures.Subject.AGENT || 'agent executor'
        ProgramFailures.Subject.PROGRAM || 'program executor'
    }

    def "FR21: an agent round with no answer file completes with no answer, its usage kept"() {
        when:
        def result = AnswerOutcome.ofAgent(Role.TRANSFORM, 'claude', new AnswerFileReading.Absent(), UntrustedText.factory(''), SPENT)

        then:
        result.status() == Status.COMPLETED
        result.answer() == null
        result.usage() == SPENT
    }

    // FR9 (design D4 as amended): a decision request leaves with no continuation key; another kind as read
    def "FR9, FR21: an agent's answer completes the round, a decision request without its key"() {
        when:
        def result = AnswerOutcome.ofAgent(Role.TRANSFORM, 'claude', new AnswerFileReading.Answered(answer), UntrustedText.factory(''), SPENT)

        then:
        result.status() == Status.COMPLETED
        result.answer().toString() == carried
        result.usage() == SPENT

        where:
        answer || carried
        new Answer.DecisionRequest(UntrustedText.agent('q?'), [
            UntrustedText.agent('a'),
            UntrustedText.agent('b')
        ], 'forged') || '{"kind":"decision-request","question":"q?","options":["a","b"]}'
        new Answer.Select(UntrustedText.agent('a'), [UntrustedText.agent('a')], null) || '{"kind":"select","chosen":"a","ranking":["a"]}'
    }

    def "FR20, FR21: a #role answer the read refused is the refused mapping's failed/infrastructure, with the round's usage"() {
        when:
        def result = AnswerOutcome.ofAgent(role, 'claude', REFUSED, UntrustedText.subprocess('boom'), SPENT)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE
        result.reason().raw() == "agent executor 'claude': wrote a malformed answer file: oops; its stderr ended with: boom"
        result.details() == null
        result.usage() == SPENT

        where:
        role << [Role.VERDICT, Role.DECISION]
    }

    // FR20 (design D15, part 3): the one finding names the cause and quotes what was written, bounded
    //     and neutralized; its details carry the same excerpt as agent text; the stderr tail is no
    //     part of a quality finding
    def "FR20: a transform answer the read refused is failed/quality naming the cause, quoting what was written, with the round's usage"() {
        when:
        def result = AnswerOutcome.ofAgent(Role.TRANSFORM, 'claude', REFUSED, UntrustedText.subprocess('boom'), SPENT)

        then:
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.QUALITY
        result.reason().raw() == "agent executor 'claude': wrote a malformed answer file: oops; it wrote: oops"
        result.reason().provenance() == Provenance.FACTORY
        result.details().raw() == 'oops'
        result.details().provenance() == Provenance.AGENT
        result.answer() == null
        result.usage() == SPENT
    }

    def "FR20: the excerpt of a transform answer is bounded and neutralized, and an answer that wrote nothing quotes nothing"() {
        given:
        def text = 'x' * (AnswerOutcome.EXCERPT_CHARS * 3) + '\u001B[31m\nforged'
        def wrote = new AnswerFileReading.Refused('wrote a malformed answer file: bad', UntrustedText.subprocess(text))
        def nothing = new AnswerFileReading.Refused('wrote an empty answer file', UntrustedText.agent(' \n'))

        when:
        def quoted = AnswerOutcome.ofAgent(Role.TRANSFORM, 'claude', wrote, UntrustedText.factory(''), SPENT)
        def empty = AnswerOutcome.ofAgent(Role.TRANSFORM, 'claude', nothing, UntrustedText.factory(''), SPENT)

        then: 'the reason quotes the excerpt after the cause, neutralized by the excerpt exit'
        def excerpt = wrote.written().excerpt(AnswerOutcome.EXCERPT_CHARS)
        quoted.reason().raw() == "agent executor 'claude': wrote a malformed answer file: bad; it wrote: " + excerpt
        !quoted.reason().raw().contains('\n') && !quoted.reason().raw().contains('\u001B')

        and: 'the details carry the same excerpt as agent text'
        quoted.details().raw() == excerpt
        quoted.details().length() <= AnswerOutcome.EXCERPT_CHARS
        !quoted.details().raw().contains('\n') && !quoted.details().raw().contains('\u001B')
        quoted.details().provenance() == Provenance.AGENT

        and:
        empty.failureClass() == FailureClass.QUALITY
        empty.reason().raw() == "agent executor 'claude': wrote an empty answer file"
        empty.details() == null
    }
}
