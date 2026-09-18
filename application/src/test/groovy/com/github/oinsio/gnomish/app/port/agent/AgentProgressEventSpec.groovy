package com.github.oinsio.gnomish.app.port.agent

import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR7, design D10 of add-agent-executor and FR1, design D4 of type-untrusted-text: the progress
 * events the parse loop emits carry what the agent itself chose — its model id, its session id and
 * its final message — as {@link UntrustedText}, so a listener renders them through an exit instead
 * of receiving a string this port already flattened.
 *
 * <p>The carrier is preserved, not copied through a rendering: a listener that logs and a listener
 * that displays need different exits, and a port that picked one for them would leave the other
 * with text it cannot un-flatten.
 */
class AgentProgressEventSpec extends Specification {

    def "FR1: RoundStarted carries the model and session id exactly as they were minted"() {
        given:
        def model = UntrustedText.agent('claude-opus-4-8[1m]')
        def sessionId = UntrustedText.agent('sess-1')

        when:
        def event = new AgentProgressEvent.RoundStarted(model, sessionId)

        then: 'both travel on as the carriers they arrived in — same text, same provenance'
        event.model() == model
        event.sessionId() == sessionId
    }

    def "FR1: RoundFinished carries the round's final message as the carrier"() {
        given:
        def summary = UntrustedText.agent("done\n[2Jwith an escape in it")

        when:
        def event = new AgentProgressEvent.RoundFinished('success', ['claude-x': new TokenUsage(1, 2, 3, 4)], summary)

        then:
        event.summary() == summary

        and: 'the port neither renders nor caps it — that is the listener\'s exit to choose'
        event.summary().forParsing() == "done\n[2Jwith an escape in it"
    }

    def "an event with a blank #component is refused, naming it"() {
        when:
        build.call()

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(component)

        where:
        component | build
        'model' | {
            new AgentProgressEvent.RoundStarted(UntrustedText.agent(' '), UntrustedText.agent('s'))
        }
        'sessionId' | {
            new AgentProgressEvent.RoundStarted(UntrustedText.agent('m'), UntrustedText.agent(''))
        }
        'name' | { new AgentProgressEvent.ToolStarted(' ') }
    }

    def "a RoundFinished with no summary at all is a mapping bug, not wire data"() {
        when:
        new AgentProgressEvent.RoundFinished('success', [:], null)

        then:
        def e = thrown(NullPointerException)
        e.message.contains('summary')
    }
}
