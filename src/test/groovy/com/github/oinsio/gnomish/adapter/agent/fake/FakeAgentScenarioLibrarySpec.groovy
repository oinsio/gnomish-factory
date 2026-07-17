package com.github.oinsio.gnomish.adapter.agent.fake

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Task 2.2, FR15, M2, D11 of add-agent-executor: covers the scripted scenario
 * library the fake agent binary ships with — plain round, decision-file
 * write, subagent events, judge verdict, garbage output, premature death —
 * asserting the harness plays each one back faithfully through a real
 * subprocess. These specs exercise the fake/harness mechanics themselves;
 * there is no CLI adapter yet to test against (that lands in groups 6–7),
 * which will parse this same stdout through the stream-json parser (group 3).
 */
class FakeAgentScenarioLibrarySpec extends Specification {

    @TempDir
    Path workDir

    // FR1, D2: a clean round emits init → tool_use/tool_result → final
    // message → a result event with modelUsage, and exits 0.
    def "plain-round emits a full clean round and writes its workspace file"() {
        given:
        def invocation = new FakeAgentInvocation(scenario: 'plain-round', workingDirectory: workDir)

        when:
        def result = invocation.run()

        then: 'a clean exit with the expected event shape'
        result.exitCode() == 0
        result.stdoutLines().size() == 5
        result.stdoutLines()[0].contains('"subtype":"init"')
        result.stdoutLines().any { it.contains('tool_use') }
        result.stdoutLines().last().contains('"type":"result"')
        result.stdoutLines().last().contains('modelUsage')

        and: 'the scripted workspace file is written into the cwd'
        Files.readString(workDir.resolve('output.txt')).trim() == 'done'
    }

    // FR3, D1: decision file present -> the adapter (once built) will map this
    // to DecisionNeeded; here we assert the fake actually writes it when wired.
    def "decision-needed writes a well-formed decision file when GNOMISH_DECISION_FILE is wired"() {
        given:
        Path decisionFile = workDir.resolve('decision.json')
        def invocation = new FakeAgentInvocation(
                scenario: 'decision-needed', workingDirectory: workDir, decisionFilePath: decisionFile)

        when:
        def result = invocation.run()

        then:
        result.exitCode() == 0
        Files.isRegularFile(decisionFile)
        Files.readString(decisionFile).contains('"question": "Refactor or patch?"')
    }

    // NFR-O2, D1: garbage decision content is still written verbatim — the
    // adapter's tolerant read (not the fake) is responsible for degrading it.
    def "decision-garbage writes unparseable content verbatim"() {
        given:
        Path decisionFile = workDir.resolve('decision.json')
        def invocation = new FakeAgentInvocation(
                scenario: 'decision-garbage', workingDirectory: workDir, decisionFilePath: decisionFile)

        when:
        def result = invocation.run()

        then:
        result.exitCode() == 0
        Files.readString(decisionFile).contains('not json at all')
    }

    // NFR-R3, D1: no decision file is written when the caller never wires
    // $GNOMISH_DECISION_FILE — mirrors "no signal means Completed".
    def "decision-needed writes nothing when no decision file path is wired"() {
        given:
        def invocation = new FakeAgentInvocation(scenario: 'decision-needed', workingDirectory: workDir)

        when:
        def result = invocation.run()

        then:
        result.exitCode() == 0
        Files.list(workDir).toList().isEmpty()
    }

    // FR6, D3: nested tool_use/tool_result events carry parent_tool_use_id so
    // a future parser can filter subagent internals from the top-level trace.
    def "subagent-round marks nested events with a parent tool-use id"() {
        given:
        def invocation = new FakeAgentInvocation(scenario: 'subagent-round', workingDirectory: workDir)

        when:
        def result = invocation.run()

        then:
        result.exitCode() == 0
        def nested = result.stdoutLines().findAll { it.contains('parent_tool_use_id') }
        nested.size() == 3
        def topLevelToolUse = result.stdoutLines().findAll {
            it.contains('"type":"tool_use"') && !it.contains('parent_tool_use_id')
        }
        topLevelToolUse.size() == 1
    }

    // FR8, D5: a clean, unfenced verdict in the final message.
    def "judge-verdict-pass carries a clean JSON verdict in the result event"() {
        given:
        def invocation = new FakeAgentInvocation(scenario: 'judge-verdict-pass', workingDirectory: workDir)

        when:
        def result = invocation.run()

        then:
        result.exitCode() == 0
        result.stdoutLines().last().contains('passed\\": true')
    }

    // FR8, D5: a fenced verdict the adapter must strip fences from.
    def "judge-verdict-fail-fenced wraps the verdict in a markdown fence"() {
        given:
        def invocation = new FakeAgentInvocation(scenario: 'judge-verdict-fail-fenced', workingDirectory: workDir)

        when:
        def result = invocation.run()

        then:
        result.exitCode() == 0
        result.stdoutLines().last().contains('```json')
        result.stdoutLines().last().contains('passed\\": false')
    }

    // FR8, NFR-R1: no parseable verdict anywhere in the final message.
    def "judge-verdict-garbage carries no structured verdict"() {
        given:
        def invocation = new FakeAgentInvocation(scenario: 'judge-verdict-garbage', workingDirectory: workDir)

        when:
        def result = invocation.run()

        then:
        result.exitCode() == 0
        !result.stdoutLines().last().contains('"passed"')
    }

    // FR4, D3: unknown/broken lines mixed with valid stream-json — tolerant
    // parsing (group 3) must ignore what it cannot read without failing the round.
    def "garbage-output mixes broken and unknown lines with valid events"() {
        given:
        def invocation = new FakeAgentInvocation(scenario: 'garbage-output', workingDirectory: workDir)

        when:
        def result = invocation.run()

        then:
        result.exitCode() == 1
        result.stdoutLines().any { it.contains('"subtype":"init"') }
        result.stdoutLines().any { !it.startsWith('{') }
        result.stdoutLines().any { it.contains('totally_unknown_event_type') }
    }

    // FR4, NFR-R1: the stream never emits a result event at all — an
    // infrastructure failure once a real parser is wired.
    def "missing-result-event never emits a result event"() {
        given:
        def invocation = new FakeAgentInvocation(scenario: 'missing-result-event', workingDirectory: workDir)

        when:
        def result = invocation.run()

        then:
        result.exitCode() == 0
        !result.stdoutLines().any { it.contains('"type":"result"') }
    }

    // FR6, D3: an orphaned tool_use with no matching tool_result and a
    // non-zero exit — the process died mid-round.
    def "premature-death exits non-zero after an orphaned tool_use"() {
        given:
        def invocation = new FakeAgentInvocation(scenario: 'premature-death', workingDirectory: workDir)

        when:
        def result = invocation.run()

        then:
        result.exitCode() == 137
        result.stdoutLines().any { it.contains('tool_use') }
        !result.stdoutLines().any { it.contains('tool_result') }
        !result.stdoutLines().any { it.contains('"type":"result"') }
    }
}
