package com.github.oinsio.gnomish.adapter.agent.runner

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.app.port.agent.AgentProgressEvent
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Usage
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * A second dialect beside the vendor's, with an event format of its own (scenario "Second dialect
 * needs no runner change"; FR2 of extract-agent-round-runner): plain words, one event per line —
 * {@code hello <model> <session>}, {@code use <tool>}, {@code done <summary>}, {@code stop <bound>},
 * {@code answer <json>} — and anything else is no event. It renders the role's input on stdin and the
 * role's word as the one argument — never a session flag, a continuation on the request included —
 * writes the answer an {@code answer} event carries itself (the word
 * {@code malformed} is an answer that does not match its schema), stops on a bound when a {@code stop}
 * event came, completes when a {@code done} event came, and is broken otherwise. It records what the
 * runner handed it, so a spec sees what the runner's path did without reaching into it.
 *
 * <p>Shared from {@code :test-fixtures} (M4 of extract-agent-round-runner): the runner's own specs in
 * {@code :adapters:agent} and the conformance kit's run of the runner in the composition root
 * ({@code AgentRoundRunnerConformanceSpec}) drive this one fake, so the seam the kit judges is the one
 * the runner's specs pin.
 */
final class FakeDialect implements AgentDialect<FakeEvent> {

    /** The credential names its describe declares. */
    List<String> credentials = []

    /** The wall time its usage reports, or null for none. */
    Long wallMillis

    final List<ExecutorRequest> rendered = []

    final List<String> parsed = []

    ParsedEvents<FakeEvent> judged

    @Override
    Describe describe() {
        new Describe(1, UntrustedText.subprocess('fake-1'), [
            Role.TRANSFORM,
            Role.VERDICT,
            Role.DECISION
        ], [],
        JsonNodeFactory.instance.objectNode(), credentials, [], null, [], EnforcementLevel.PROMPT_ONLY, true, false)
    }

    @Override
    RenderedArgv render(ExecutorRequest request) {
        rendered << request
        def inputs = request.roleInputs()
        new RenderedArgv([
            'fake-agent',
            request.role().wire()
        ], inputs.instructions() ?: inputs.criteria() ?: inputs.question())
    }

    @Override
    ParsedLine<FakeEvent> parse(String line, ParsedEvents<FakeEvent> earlier) {
        parsed << line
        def words = line.split(' ', 2)
        def event = new FakeEvent(words[0], words.length> 1 ? words[1] : '')
        switch (event.kind) {
            case 'hello':
                return new ParsedLine<FakeEvent>([event], [
                    new AgentProgressEvent.RoundStarted(UntrustedText.agent(event.payload.split(' ')[0]))
                ])
            case 'use':
                return new ParsedLine<FakeEvent>([event], [
                    new AgentProgressEvent.ToolStarted(UntrustedText.agent(event.payload))
                ])
            case 'done':
                return new ParsedLine<FakeEvent>([event], [
                    new AgentProgressEvent.RoundFinished([:], UntrustedText.agent(event.payload))
                ])
            case ['stop', 'answer']:
                return new ParsedLine<FakeEvent>([event], [])
            default:
                return ParsedLine.nothing()
        }
    }

    @Override
    Usage usage(ParsedEvents<FakeEvent> events, Instant roundEnd) {
        new Usage(UsageProvenance.REPORTED, wallMillis, [:], [])
    }

    @Override
    DialectAnswer answer(ExecutorRequest request, ParsedEvents<FakeEvent> events, Path answerFile) {
        def answer = events.stamped()*.event().find { it.kind == 'answer' }
        if (answer == null) {
            return new DialectAnswer.NotWritten()
        }
        if (answer.payload == 'malformed') {
            return new DialectAnswer.Malformed(UntrustedText.agent('no verdict field'))
        }
        Files.writeString(answerFile, answer.payload)
        new DialectAnswer.Written()
    }

    @Override
    RoundStatus status(int exitCode, ParsedEvents<FakeEvent> events) {
        judged = events
        def kinds = events.stamped()*.event()
        def stop = kinds.find { it.kind == 'stop' }
        if (stop != null) {
            return new RoundStatus.LimitReached(stop.payload)
        }
        kinds.any {
            it.kind == 'done'
        } ? new RoundStatus.Completed() : new RoundStatus.Broken(UntrustedText.agent('no result event'))
    }
}

/** One event of {@link FakeDialect}: its kind word and the rest of its line. */
@groovy.transform.Immutable
final class FakeEvent {
    String kind
    String payload
}
