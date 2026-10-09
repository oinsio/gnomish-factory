package com.github.oinsio.gnomish.e2e.paidsmoke

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.e2e.E2eGitTree
import com.github.oinsio.gnomish.e2e.E2eProcessHarness
import com.github.oinsio.gnomish.e2e.E2eProcessResult
import groovy.transform.TypeChecked
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.InstantSource
import java.util.concurrent.TimeUnit
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The permission mode and the MCP exclusion on the stock {@code claude} binary (task 6.2 of
 * fix-operator-blockers): one real {@code gnomish run}, host-bound, whose executor round must
 * edit a file and whose judge vote is invited to start a sub-agent. The CLI is reached through
 * {@link ArgvRecorder}, so every assertion reads the argv the factory built and the CLI's own
 * {@code system/init} event — the only layer that notices a renamed mode token (design, Risks).
 *
 * <p>Two open questions are <b>recorded, not asserted</b>, as {@code PAID-SMOKE 6.2} lines on
 * standard output: whether {@code dontAsk} denies the judge a sub-agent (proposal Q1, NG2), and
 * which value the CLI honours when a leftover operator wrapper duplicates
 * {@code --permission-mode} ahead of the factory's (design, Migration Plan).
 *
 * <p>The run is host-bound through its registered project's file, the one place the
 * sandbox-boundary key {@code factory.bindings.default} is read from (NFR-S1 of add-project-registry).
 *
 * <p>Runs only under {@code paidSmokeTest}; spends real money.
 *
 * <p>Implements FR1, FR2, FR3, M1 of fix-operator-blockers.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class PaidSmokePermissionModeSpec extends Specification {

    @Shared
    @TempDir
    Path scratch

    @Shared
    Path realBinary

    @Shared
    Path project

    @Shared
    E2eProcessResult run

    @Shared
    List<ArgvRecorder.Round> rounds

    def setupSpec() {
        realBinary = resolveOnPath(System.getProperty('paidSmoke.claudeBinary', 'claude'))
        def preflight = ClaudeLoginPreflight.check(realBinary.toString(), Files.createDirectories(scratch.resolve('preflight')))
        if (!preflight.loggedIn) {
            throw new IllegalStateException("paidSmokeTest: claude CLI preflight failed — ${preflight.reason}")
        }
        project = E2eGitTree.copyOf('paid-smoke-permission')
        E2eProcessHarness.projectConfig(project, 'factory:\n  bindings:\n    default: host\n')
        def recorder = ArgvRecorder.create(realBinary, Files.createDirectories(scratch.resolve('recorder')))
        run = new E2eProcessHarness().run(project, [
            "--dir=${project}".toString(),
            '--task=create hello.txt',
            '--mode=in-place',
            "--factory.agent-cli-binary=${recorder.script}".toString()
        ], [])
        rounds = recorder.rounds()
        report('run exit code', run.exitCode())
        rounds.each { report('recorded round', it) }
    }

    def "FR1, FR3: the executor round edits a file with the stock claude binary"() {
        given:
        def executor = rounds.find { it.argvMode() == 'acceptEdits' }

        expect: 'the factory launched an executor round with the policy flags'
        executor != null
        executor.argv.contains('--strict-mcp-config')

        and: 'the CLI accepted the mode and loaded no MCP server'
        executor.init().permissionMode == 'acceptEdits'
        (executor.init().mcp_servers ?: []).isEmpty()

        and: 'the edit was applied, not denied'
        Files.readString(project.resolve('hello.txt')).contains('hello')
        (executor.result().permission_denials ?: []).isEmpty()
    }

    def "FR2, FR3: the judge vote runs in dontAsk and finishes without waiting for an answer (Q1 recorded)"() {
        given:
        def judge = rounds.find { it.argvMode() == 'dontAsk' }

        expect:
        judge != null
        judge.argv.contains('--strict-mcp-config')
        judge.init().permissionMode == 'dontAsk'
        (judge.init().mcp_servers ?: []).isEmpty()
        judge.result() != null

        cleanup:
        report('Q1 judge tool calls', judge?.toolCalls())
        report('Q1 judge permission_denials', judge?.result()?.permission_denials)
        report('D1 judge round finished (no prompt waited on)', judge?.result() != null)
    }

    def "Migration: a wrapper's --permission-mode ahead of the judge's — which value the CLI honours"() {
        given: 'the judge argv the factory built, with a leftover wrapper flag in front of it'
        def judge = rounds.find { it.argvMode() == 'dontAsk' }
        assert judge != null
        Path box = Files.createDirectories(scratch.resolve('duplicate'))
        List<String> command = [
            realBinary.toString(),
            '--permission-mode',
            'acceptEdits'
        ] + judge.argv

        when:
        def events = launch(command, box, 'Create a file named probe.txt containing ok with the Write tool, then reply done.')
        def init = events.find { it.type == 'system' && it.subtype == 'init' }

        then:
        (init?.permissionMode as String) in ['acceptEdits', 'dontAsk']

        cleanup:
        report('duplicated --permission-mode honoured', init?.permissionMode)
        report('duplicated --permission-mode: judge-argv round wrote a file', Files.exists(box.resolve('probe.txt')))
    }

    @TypeChecked
    private static List<Map> launch(List<String> command, Path box, String prompt) {
        // real-time-wiring: real wall time, unchanged from the deleted domain clock adapter
        //     (FR17 of supervise-daemon-loops-and-embed-dashboard); the time source is not the subject here.
        def clock = InstantSource.system()
        def handle = PaidSmokeAgentLauncher.launch(command, box, clock, prompt)
        List<String> lines = handle.output().withReader(StandardCharsets.UTF_8.name()) {
            it.readLines()
        }
        handle.waitForExitOrTimeout(Duration.ofSeconds(90), clock)
        def mapper = new ObjectMapper()
        lines.findAll {
            it.startsWith('{')
        }.collect {
            mapper.readValue(it, Map)
        }
    }

    @TypeChecked
    private static Path resolveOnPath(String binary) {
        if (binary.contains('/')) {
            return Path.of(binary).toAbsolutePath()
        }
        Path found = System.getenv('PATH').split(File.pathSeparator).toList()
                .collect { String dir -> Path.of(dir, binary) }
                .find { Path candidate -> Files.isExecutable(candidate) }
        if (found == null) {
            throw new IllegalStateException("'${binary}' not on PATH")
        }
        found
    }

    private static void report(String what, Object value) {
        println "PAID-SMOKE 6.2 | ${what}: ${value}"
    }
}
