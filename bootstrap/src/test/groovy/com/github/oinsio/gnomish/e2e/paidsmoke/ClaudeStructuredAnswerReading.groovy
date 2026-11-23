package com.github.oinsio.gnomish.e2e.paidsmoke

import com.fasterxml.jackson.databind.JsonNode
import com.github.oinsio.gnomish.adapter.agent.claude.AgentEvent
import com.github.oinsio.gnomish.adapter.agent.claude.ContentBlock
import com.github.oinsio.gnomish.adapter.agent.runner.ParsedEvents
import com.github.oinsio.gnomish.adapter.agent.runner.RenderedArgv
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import groovy.transform.CompileStatic

/**
 * How {@link PaidSmokeStructuredAnswerSpec} reads a Claude round (FR22 of extract-agent-round-runner,
 * design D15 "A platform fact to measure"): the schema the rendered argv hands the CLI, the structured
 * output of the stream's result event — never the final text the dialect falls back to — and the
 * round's tool calls, without the CLI's own {@code StructuredOutput} call that returns the reply. The
 * spec stays dialect-agnostic; a new dialect brings a reading of its own.
 *
 * <p>Statically compiled for the reason {@link PaidSmokeAgentLauncher} states.
 *
 * <p>Implements FR22 of extract-agent-round-runner.
 */
@CompileStatic
final class ClaudeStructuredAnswerReading {

    /** The flag the Claude dialect passes the reply's schema with. */
    static final String JSON_SCHEMA_FLAG = '--json-schema'

    /** The CLI's own tool that returns the structured reply; not one of the round's work calls. */
    static final String STRUCTURED_OUTPUT_TOOL = 'StructuredOutput'

    private ClaudeStructuredAnswerReading() {}

    /** The schema the argv carries, or null when it carries none. */
    static JsonNode schema(RenderedArgv rendered) {
        int at = rendered.argv().indexOf(JSON_SCHEMA_FLAG)
        at < 0 ? null : ExecutorContractJson.reader().readTree(rendered.argv()[at + 1])
    }

    /** The result event's structured output, or null when the stream has no result event or it has none. */
    static JsonNode structuredOutput(ParsedEvents<AgentEvent> events) {
        events.last(AgentEvent.ResultEvent)?.structuredOutput()
    }

    /** The tool calls the round's assistant messages made, the CLI's structured-output call excluded. */
    static int toolCalls(ParsedEvents<AgentEvent> events) {
        int calls = 0
        events.stamped().each { ParsedEvents.Stamped<AgentEvent> stamped ->
            if (stamped.event() instanceof AgentEvent.AssistantEvent) {
                calls += (stamped.event() as AgentEvent.AssistantEvent).content().count { ContentBlock block ->
                    block instanceof ContentBlock.ToolUse && (block as ContentBlock.ToolUse).name() != STRUCTURED_OUTPUT_TOOL
                } as int
            }
        }
        calls
    }
}
