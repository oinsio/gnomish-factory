package com.github.oinsio.gnomish.e2e.paidsmoke

import com.github.oinsio.gnomish.adapter.agent.claude.ClaudeDialect
import com.github.oinsio.gnomish.adapter.agent.runner.AgentDialect
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.describe.QuietStdoutBounds
import com.github.oinsio.gnomish.executorcontract.Bounds
import com.github.oinsio.gnomish.executorcontract.ExecutorContractJson
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
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
 * A dialect's declared liveness, measured on the real CLI (FR17 of extract-agent-round-runner;
 * design D6, "A declared liveness is tested, per dialect"): for every dialect whose describe
 * declares {@code streams_progress} — the declaration the factory's silence bound is applied on,
 * read through {@link QuietStdoutBounds} — and every platform of {@link PaidSmokePlatform}, the real
 * CLI is started with the argv the dialect renders for a {@code transform} request and asked for one
 * tool call that prints nothing for 420 seconds, in the main agent and, where the dialect has
 * subagents, inside one. Every stdout line is stamped on arrival; the call must run to its end, and
 * the largest gap between two lines must be at most a fifth of {@code ProgramBounds.DEFAULTS.silence()}.
 *
 * <p>A new dialect joins {@link #DIALECTS}; one that does not declare the capability yields no row.
 * With no image named by {@code -PpaidSmoke.linuxImage} the container rows fail, never skip.
 *
 * <p>Runs only under {@code paidSmokeTest}; spends real money and about eight minutes a row. The
 * human runs it on a CLI version bump and before a change that adds or changes a dialect is
 * archived.
 *
 * <p>Implements FR17 of extract-agent-round-runner.
 */
class PaidSmokeLivenessSpec extends Specification {

    static final Duration QUIET_CALL = Duration.ofSeconds(420)

    static final String QUIET_COMMAND = "perl -e 'select(undef, undef, undef, 420)'"

    /** Each dialect the factory ships, with whether it has subagents and how it is built. */
    static final List<Map> DIALECTS = [
        [name: 'claude', subagents: true, build: { String binary, UntrustedText version, ChildEnvAllowlist env ->
                ClaudeDialect.of(binary, version, env)
            }],
    ]

    static final String MAIN_PROMPT = "Use the Bash tool exactly once, in the foreground (never in the background), " +
    "with its timeout set to 600000 milliseconds, to run this command, which prints nothing and takes " +
    "${QUIET_CALL.seconds} seconds: ${QUIET_COMMAND}\nWait for it to finish. Then reply with the single word: done"

    static final String SUBAGENT_PROMPT = 'Start exactly one subagent with the Task tool and wait for it in the ' +
    'foreground. Do not run any command yourself. Give the subagent exactly this instruction:\n\n' + MAIN_PROMPT +
    '\n\nWhen the subagent has returned, reply with the single word: done'

    @TempDir
    Path scratch

    // Scenario: executor/claude-dialect / A quiet tool call in the main agent is reported within a fifth of the silence bound
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    def "FR17: #dialect.name on #platform reports a quiet tool call in the main agent within a fifth of the silence bound"() {
        expect:
        quietCallIsReported(dialect, platform, MAIN_PROMPT, 'main agent')

        where:
        [dialect, platform] << rows(false)
    }

    // Scenario: executor/claude-dialect / A quiet tool call inside a subagent is reported within a fifth of the silence bound
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    def "FR17: #dialect.name on #platform reports a quiet tool call inside a subagent within a fifth of the silence bound"() {
        expect:
        quietCallIsReported(dialect, platform, SUBAGENT_PROMPT, 'subagent')

        where:
        [dialect, platform] << rows(true)
    }

    /** Every declaring dialect (with subagents, when asked) crossed with every platform. */
    static List<List<Object>> rows(boolean subagent) {
        DIALECTS.findAll { Map dialect ->
            declaresLiveness(dialect) && (!subagent || dialect.subagents)
        }.collectMany { Map dialect ->
            PaidSmokePlatform.values().collect { [dialect, it] as List<Object> }
        }
    }

    private static boolean declaresLiveness(Map dialect) {
        def probe = (dialect.build as Closure<AgentDialect>).call('probe', UntrustedText.subprocess('probe'),
                TestChildEnvironment.hostAllowlist())
        QuietStdoutBounds.applyTo(probe.describe())
    }

    private boolean quietCallIsReported(Map row, PaidSmokePlatform platform, String prompt, String where) {
        // real-time-wiring: the subject is the real CLI's cadence on the wall clock; every line is
        //     stamped on arrival, and virtual time would measure nothing.
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
        def rendered = dialect.render(transform(prompt))

        def handle = PaidSmokeAgentLauncher.launch(platform.command(rendered.argv(), credentials), workspace, clock,
                rendered.stdin(), allowlist)
        def stdout = StampedStdout.read(handle, clock)
        handle.waitForExitOrTimeout(Duration.ofMinutes(1), clock)

        def answer = stdout.last('"type":"result"')
        def gap = stdout.largestGap()
        def bound = ProgramBounds.DEFAULTS.silence().dividedBy(5)
        def subject = "dialect ${row.name}, platform ${platform.label()}, CLI ${version}, call in the ${where}: " +
                "largest gap ${gap.toMillis()} ms over ${stdout.lines.size()} lines"
        assert stdout.last('select(undef') != null : "${subject} — the quiet tool call was never made"
        assert answer != null : "${subject} — no final answer arrived"
        assert stdout.sinceStart(answer) >= QUIET_CALL : "${subject} — the final answer arrived after " +
        "${stdout.sinceStart(answer).seconds} s, before the ${QUIET_CALL.seconds} s call could have ended"
        assert gap <= bound : "${subject} — above a fifth of the silence bound (${bound.toMillis()} ms)"
        true
    }

    private static ExecutorRequest transform(String prompt) {
        // The model is the one the cheapest paid run can name; the cadence is the CLI's, not the model's.
        def settings = [model: 'sonnet', allowedTools: ['Bash', 'Task', 'Agent']]
        new ExecutorRequest(Role.TRANSFORM, new Workspace('.'), new Policy.WorkspaceWrite(), RoleInputs.transform(prompt),
                ExecutorContractJson.settingsTree(settings), Bounds.none(), [], null)
    }
}
