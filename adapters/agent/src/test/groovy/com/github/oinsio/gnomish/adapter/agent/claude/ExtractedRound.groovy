package com.github.oinsio.gnomish.adapter.agent.claude

import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant

/**
 * What the dialect reads out of one parsed Claude stream, for the specs that check the parse against
 * recorded fixtures: the last result event's session id and result text, and the usage {@link
 * AgentRoundResultExtractor#usage} derives. The old round path's {@code extract} returned the same
 * triple until task 5.1 of extract-agent-round-runner deleted it.
 */
final class ExtractedRound {

    final UntrustedText sessionId
    final UntrustedText result
    final ExecutorUsage usage

    private ExtractedRound(UntrustedText sessionId, UntrustedText result, ExecutorUsage usage) {
        this.sessionId = sessionId
        this.result = result
        this.usage = usage
    }

    static ExtractedRound of(AgentRoundResultExtractor extractor, List<TimestampedEvent> events, Instant roundEnd) {
        def last = AgentRoundResultExtractor.last(events, AgentEvent.ResultEvent)
        assert last != null: 'the fixture carries a result event'
        new ExtractedRound(last.sessionId(), last.result(), extractor.usage(events, roundEnd))
    }

    UntrustedText sessionId() {
        sessionId
    }

    UntrustedText result() {
        result
    }

    ExecutorUsage usage() {
        usage
    }
}
