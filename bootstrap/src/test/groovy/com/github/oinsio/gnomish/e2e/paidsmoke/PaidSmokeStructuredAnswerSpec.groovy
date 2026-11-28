package com.github.oinsio.gnomish.e2e.paidsmoke

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.oinsio.gnomish.adapter.agent.claude.ClaudeDialect
import com.github.oinsio.gnomish.adapter.agent.runner.AgentDialect
import com.github.oinsio.gnomish.adapter.agent.runner.AnswerForm
import com.github.oinsio.gnomish.adapter.agent.runner.DialectAnswer
import com.github.oinsio.gnomish.adapter.agent.runner.ParsedEvents
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.SettingsJudgement
import com.github.oinsio.gnomish.executorcontract.SettingsSchema
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.InstantSource
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * A dialect's declared schema-enforced answer, measured on the real CLI (FR22 of
 * extract-agent-round-runner; design D15, "A platform fact to measure"): for every dialect whose
 * {@code answerForm} of a role is {@link AnswerForm.SchemaEnforced} and every platform of {@link
 * PaidSmokePlatform}, the real CLI is started with the argv the dialect renders — the reply's schema
 * included — for a {@code transform} round of at least {@link #TOOL_CALLS} tool calls whose
 * instructions end by requiring a decision, and for a {@code verdict} on criteria that ask for a
 * detailed written review. The round's final structured output must be present (never the dialect's
 * final-text fallback), valid against the schema the argv carried, judged by the factory's one JSON
 * Schema judge, and the dialect's answer step must make a contract answer of it.
 *
 * <p>A new dialect joins {@link #DIALECTS} with a reading of its own, as {@link
 * ClaudeStructuredAnswerReading} reads Claude's argv and stream; one that declares the file form for a role yields no row for it.
 * With no image named by {@code -PpaidSmoke.linuxImage} the container rows fail, never skip.
 *
 * <p>Runs only under {@code paidSmokeTest}; spends real money and a few minutes a row. The human runs
 * it on a CLI version bump and before a change that adds or changes a dialect is archived.
 *
 * <p>Implements FR22 of extract-agent-round-runner.
 */
class PaidSmokeStructuredAnswerSpec extends Specification {

    static final int TOOL_CALLS = 50

    /** Each dialect the factory ships: how it is built, and how its stream and argv are read. */
    static final List<Map> DIALECTS = [
        [name: 'claude',
            build: { String binary, UntrustedText version, ChildEnvAllowlist env ->
                ClaudeDialect.of(binary, version, env)
            },
            schema: ClaudeStructuredAnswerReading.&schema,
            structuredOutput: ClaudeStructuredAnswerReading.&structuredOutput,
            toolCalls: ClaudeStructuredAnswerReading.&toolCalls],
    ]

    static final String LONG_TRANSFORM = "This round measures a long run. Make exactly ${TOOL_CALLS} separate " +
    "Bash tool calls, one after another, in the foreground, never two commands in one call: in call number N, " +
    "for N from 1 to ${TOOL_CALLS}, run the command: printf 'entry N\\n' > entry-N.txt (with N replaced by the " +
    "number). When all ${TOOL_CALLS} calls are done, you cannot proceed without a human decision: the next file " +
    "must be named after one of alpha, beta or gamma, and only a human may choose which. Ask that decision, " +
    "offering exactly those three options, and finish your turn."

    static final String PROSE_CRITERIA = '''The change under review is this function:

```java
static int average(int[] values) {
    int sum = 0;
    for (int i = 0; i <= values.length; i++) { sum += values[i]; }
    return sum / values.length;
}
```

Acceptance criteria: write a detailed written review of this function. For every defect, explain in
several sentences what goes wrong, quote the offending line in backticks, show a corrected version as a
code span, and discuss the edge cases (an empty array, overflow, rounding). The change passes only if
the function has no defect.'''

    @TempDir
    Path scratch

    // Scenario: executor/claude-dialect / A long transform round ends with a schema-valid decision request
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    def "FR22: #dialect.name on #platform ends a long transform round with a schema-valid decision request"() {
        when:
        def round = structuredAnswer(dialect, platform) { AgentDialect agent ->
            transform(LONG_TRANSFORM + '\n\n' + agent.answerForm(Role.TRANSFORM).instruction())
        }

        then:
        round.toolCalls >= TOOL_CALLS
        round.answer instanceof DialectAnswer.Replied
        (round.answer as DialectAnswer.Replied).document().path('kind').asText() == 'decision-request'
        (round.answer as DialectAnswer.Replied).document().path('options').size() >= 2

        where:
        [dialect, platform] << rows(Role.TRANSFORM)
    }

    // Scenario: executor/claude-dialect / A verdict on prose-inviting criteria is schema-valid
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    def "FR22: #dialect.name on #platform answers a verdict on prose-inviting criteria with a schema-valid reply"() {
        when:
        def round = structuredAnswer(dialect, platform) { AgentDialect agent ->
            verdict(PROSE_CRITERIA)
        }

        then:
        round.answer instanceof DialectAnswer.Replied

        where:
        [dialect, platform] << rows(Role.VERDICT)
    }

    /** Every dialect declaring the schema-enforced form for {@code role}, crossed with every platform. */
    static List<List<Object>> rows(Role role) {
        DIALECTS.findAll { Map dialect ->
            def probe = (dialect.build as Closure<AgentDialect>).call('probe', UntrustedText.subprocess('probe'),
            TestChildEnvironment.hostAllowlist())
            probe.answerForm(role) instanceof AnswerForm.SchemaEnforced
        }.collectMany { Map dialect ->
            PaidSmokePlatform.values().collect { [dialect, it] as List<Object> }
        }
    }

    /**
     * Runs the request {@code requestOf} builds on the real CLI, asserts the round's structured output is
     * present and valid against the schema its argv carried, and returns the tool-call count and the
     * dialect's answer.
     */
    private Map structuredAnswer(Map row, PaidSmokePlatform platform, Closure<ExecutorRequest> requestOf) {
        // real-time-wiring: the subject is the real CLI on its platform; the launcher's waits and the
        //     arrival stamps run on the wall clock, and virtual time would measure nothing.
        def clock = InstantSource.system()
        def hostBinary = System.getProperty('paidSmoke.claudeBinary', 'claude')
        def workspace = Files.createDirectories(scratch.resolve('workspace'))
        platform.requireLogin(hostBinary, Files.createDirectories(scratch.resolve('preflight')))
        def probe = (row.build as Closure<AgentDialect>).call('probe', UntrustedText.subprocess('probe'),
                TestChildEnvironment.hostAllowlist())
        List<String> credentials = probe.describe().credentials()
        def allowlist = TestChildEnvironment.hostAllowlist(credentials + PaidSmokePlatform.dockerClientNames(), [])
        def version = platform.version(hostBinary, workspace, clock, allowlist)
        def dialect = (row.build as Closure<AgentDialect>).call(platform.binary(hostBinary),
                UntrustedText.subprocess(version), allowlist)
        def request = requestOf.call(dialect)
        def rendered = dialect.render(request)

        def handle = PaidSmokeAgentLauncher.launch(platform.command(rendered.argv(), credentials), workspace, clock,
                rendered.stdin(), allowlist)
        def stdout = StampedStdout.read(handle, clock)
        handle.waitForExitOrTimeout(Duration.ofMinutes(1), clock)
        def events = parsed(dialect, stdout)

        def subject = "dialect ${row.name}, platform ${platform.label()}, CLI ${version}, ${request.role().wire()} round"
        JsonNode schema = (row.schema as Closure<JsonNode>).call(rendered)
        JsonNode reply = (row.structuredOutput as Closure<JsonNode>).call(events)
        assert schema != null : "${subject} — the rendered argv carries no reply schema"
        assert reply instanceof ObjectNode : "${subject} — the result event carries no structured output " +
        "(last line: ${stdout.lines ? stdout.lines.last().text : 'none'})"
        def judgement = SettingsSchema.judge(schema, ExecutorContractJson.settingsValues(reply as ObjectNode))
        assert judgement instanceof SettingsJudgement.Accepted : "${subject} — the structured output ${reply} " +
        "is not valid against the schema its argv carried: ${judgement}"
        [toolCalls: (row.toolCalls as Closure<Integer>).call(events), answer: dialect.answer(request, events)]
    }

    /** The round's stdout parsed by the dialect, each event stamped with its line's arrival. */
    private static ParsedEvents parsed(AgentDialect dialect, StampedStdout stdout) {
        List<ParsedEvents.Stamped> stamped = []
        stdout.lines.each { StampedStdout.Line line ->
            dialect.parse(line.text, new ParsedEvents(stamped)).events().each {
                stamped << new ParsedEvents.Stamped(line.at, it)
            }
        }
        new ParsedEvents(stamped)
    }

    private static ExecutorRequest transform(String instructions) {
        // The model and tool set of the 2026-11-23 measurement's transform rows (design D15).
        def settings = [model: 'sonnet', allowedTools: ['Bash']]
        new ExecutorRequest(Role.TRANSFORM, new Workspace('.'), new Policy.WorkspaceWrite(), RoleInputs.transform(instructions),
                ExecutorContractJson.settingsTree(settings), Bounds.none(), [], null)
    }

    private static ExecutorRequest verdict(String criteria) {
        // The model and read-only policy of the 2026-11-23 measurement's verdict rows (design D15).
        new ExecutorRequest(Role.VERDICT, new Workspace('.'), new Policy.ReadOnly(), RoleInputs.verdict(criteria),
                ExecutorContractJson.settingsTree([model: 'opus']), Bounds.none(), [], null)
    }
}
