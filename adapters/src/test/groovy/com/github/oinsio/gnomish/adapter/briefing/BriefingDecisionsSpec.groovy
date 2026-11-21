package com.github.oinsio.gnomish.adapter.briefing

import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * The briefing's decisions section renders a decision that answers a decision request as the
 * question, its options and the reply — through the one renderer the stage round prompt and the
 * judge prompt both call — and any other decision as author and body (design D4 as amended
 * 2026-11-21, D8 row "The question a decision answers" of extract-agent-round-runner).
 *
 * <p>FR9, NFR-R2 of extract-agent-round-runner.
 */
class BriefingDecisionsSpec extends Specification {

    private static String rendered(List<Decision> decisions) {
        def out = new StringBuilder()
        BriefingSections.renderDecisions(out, decisions)
        out.toString()
    }

    // FR9: the question, each option and the reply, the reply attributed
    def "a decision answering a request renders its question, its options and the reply"() {
        given:
        def asked = new EscalationReport.DecisionNeeded(UntrustedText.agent('Refactor or patch?'), [
            UntrustedText.agent('refactor'),
            UntrustedText.agent('patch')
        ])

        expect:
        rendered([
            new Decision('refactor', 'build', 'operator', null, asked)
        ]) == '''\
=== Decisions ===
- question: Refactor or patch?
  options:
    * refactor
    * patch
  reply (operator): refactor

'''
    }

    // FR9: an open question says it offered no options; an unattributed reply says so
    def "an open question renders without options, and an unattributed reply as such"() {
        given:
        def asked = new EscalationReport.DecisionNeeded(UntrustedText.agent('Which library?'), [])

        expect:
        rendered([
            new Decision('use X', null, null, null, asked)
        ]) == '''\
=== Decisions ===
- question: Which library?
  options: (none, an open question)
  reply (unattributed): use X

'''
    }

    // FR9: the question and the options leave their carriers through the console exit — a control
    //     sequence the gnome wrote is made visible, never passed to the next round raw
    def "the question and the options are rendered through the console exit"() {
        given:
        def asked = new EscalationReport.DecisionNeeded(UntrustedText.agent('A\u001B[2K?'), [
            UntrustedText.agent('b\u0007')
        ])

        when:
        def text = rendered([
            new Decision('a', null, 'tracker', null, asked)
        ])

        then:
        text.contains("- question: ${UntrustedText.agent('A\u001B[2K?').forConsole()}\n")
        text.contains("    * ${UntrustedText.agent('b\u0007').forConsole()}\n")
        !text.contains('\u001B')
        !text.contains('\u0007')
    }

    // FR9: a decision without a question keeps the author-and-body line, beside one with a question
    // Scenario: executor/agent-executor / A decision without a question renders as author and body
    def "a decision recording no question renders as author and body only"() {
        given:
        def asked = new EscalationReport.DecisionNeeded(UntrustedText.agent('Q?'), [])

        expect:
        rendered([
            new Decision('retry after the fix', 'build', 'tracker', null),
            new Decision('ok', 'build', 'operator', null, asked)
        ]) == '''\
=== Decisions ===
- tracker: retry after the fix
- question: Q?
  options: (none, an open question)
  reply (operator): ok

'''
    }
}
