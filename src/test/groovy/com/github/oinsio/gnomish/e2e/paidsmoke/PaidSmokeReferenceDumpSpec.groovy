package com.github.oinsio.gnomish.e2e.paidsmoke

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.AgentProcessLauncher
import com.github.oinsio.gnomish.adapter.agent.LaunchedAgentProcess
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The paid smoke task's substance (task 11.3, M4, D11's "(3b) Paid smoke"): drives real {@code
 * claude -p --output-format stream-json --verbose} rounds through {@link AgentProcessLauncher} —
 * the same production launcher {@code CliStageExecutor}/{@code CliJudgeVoter} use — and overwrites
 * the committed {@code stream-json-reference/*.reference.json} fixtures with the real transcripts
 * (sensitive data scrubbed via {@link ReferenceDumpScrubber}), refreshing the reference set task
 * 3.6 built as hand-authored placeholders (see {@code
 * stream-json-reference/README.md}'s "PLACEHOLDER — not yet byte-real" section).
 *
 * <p><b>Never runs in CI, never part of {@code check}/{@code test}/{@code build}:</b> only the
 * dedicated {@code paidSmokeTest} Gradle task includes this package. Spend is real and deliberate
 * (M4) — run manually on CLI version bumps, parser changes, unexplained divergence between this
 * layer and the fake-agent/Ollama layers, and before archiving this change (D11).
 *
 * <p><b>Fails fast, never hangs:</b> {@link ClaudeLoginPreflight#check} runs once in {@link
 * #setupSpec} and must prove a working, logged-in CLI (a cheap real round that produces a result
 * event) before any recording round starts; on failure {@code setupSpec} throws with {@link
 * ClaudeLoginPreflight.Result#reason} as the message, which Spock/JUnit reports as a clear
 * initialization FAILURE for every feature — deliberately not a silent SKIPPED, since this task
 * runs manually and a skip could be mistaken for "nothing to record." Never a hang, never a bare
 * stack trace with no actionable cause.
 *
 * <p><b>Known gap — {@code result-without-model-usage}:</b> a {@code result} event that omits
 * {@code modelUsage} entirely is an older-CLI wire shape (design D4); the current CLI always
 * reports {@code modelUsage}, so this scenario cannot be forced from a live round and is not
 * refreshed here — it remains the hand-authored placeholder task 3.6 committed, documented as such
 * in {@code stream-json-reference/README.md}.
 *
 * <p><b>Write target:</b> the {@code paidSmokeTest} Gradle task passes the on-disk {@code
 * stream-json-reference} resource directory via the {@code referenceDumpDir} system property (the
 * same idiom as {@code e2e.jarPath} elsewhere in this build) — resource directories under {@code
 * build/resources/test} are copies, so writing there would never reach the committed fixture.
 *
 * <p>Implements M4, D11, Q1 of add-agent-executor.
 */
class PaidSmokeReferenceDumpSpec extends Specification {

    @TempDir
    Path workspaceRoot

    private final FactoryProperties factoryProperties =
    new FactoryProperties('paid-smoke', System.getProperty('paidSmoke.claudeBinary', 'claude'), List.of())

    private final SystemClock clock = new SystemClock()

    private final AgentProcessLauncher launcher = new AgentProcessLauncher(clock)

    def setupSpec() {
        // Runs once for the whole spec (not per-feature): a single small real round proves login,
        // shared across every recording feature below rather than paid once per feature. A failure
        // here throws, which JUnit/Spock reports as an initialization FAILURE for every feature in
        // this spec — fail-fast with a specific, actionable cause (ClaudeLoginPreflight.Result#reason),
        // never a hang and never a silent SKIPPED that could be mistaken for "nothing to record."
        Path probeDir = Files.createTempDirectory('paid-smoke-preflight')
        def preflightResult = ClaudeLoginPreflight.check(
                System.getProperty('paidSmoke.claudeBinary', 'claude'), probeDir)
        if (!preflightResult.loggedIn) {
            throw new IllegalStateException(
            "paidSmokeTest: claude CLI preflight failed — ${preflightResult.reason}")
        }
    }

    def "records a real plain round (single model, top-level Read/Write tools) into plain-round.reference.json"() {
        given: 'a scratch workspace with a spec file the round is instructed to read then act on'
        Files.writeString(workspaceRoot.resolve('spec.md'), "# Spec\nWrite output.txt containing 'done'.\n")

        expect:
        recordScenario(
                'plain-round',
                'plain',
                'Read spec.md, then write output.txt with the exact content it asks for. '
                + 'Finish with one short sentence confirming what you wrote.')
    }

    def "records a real subagent round (Task delegation, multi-model tokensByModel) into subagent-round.reference.json"() {
        expect:
        recordScenario(
                'subagent-round',
                'subagent',
                'Use the Task tool to delegate a one-line check to a subagent: ask it to confirm '
                + 'the current directory is writable. After the subagent reports back, write '
                + "output.txt containing 'done'. Finish with one short sentence.")
    }

    def "records a real judge-shaped round (read-only tools, fenced JSON verdict) into judge-verdict.reference.json"() {
        given: 'a scratch workspace with the file a judge round would grade'
        Files.writeString(workspaceRoot.resolve('output.txt'), 'done')

        expect:
        recordScenario(
                'judge-verdict',
                'judge',
                'Using only Read and Grep, check whether output.txt exists and contains the word '
                + '"done". Reply with nothing but a fenced JSON object: '
                + '```json\n{"passed": true, "findings": []}\n``` '
                + '(or "passed": false with findings if it does not).')
    }

    /**
     * Runs one real round, scrubs its raw stdout lines, and overwrites {@code
     * <fixtureName>.reference.json} under the resolved {@code stream-json-reference} directory —
     * task 3.6's committed dump this scenario refreshes.
     */
    private boolean recordScenario(String fixtureName, String scenarioLabel, String prompt) {
        DirectoryWorkspace workspace = new DirectoryWorkspace(workspaceRoot)
        LaunchedAgentProcess launched = launcher.launch(workspace, prompt, factoryProperties)
        assert launched != null: "'${factoryProperties.agentCliBinary()}' failed to start"

        List<String> rawLines
        try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(launched.process().inputStream, StandardCharsets.UTF_8))) {
            rawLines = reader.readLines()
        }
        launched.waitForExitMeasuringWallTime(clock)

        assert !rawLines.isEmpty(): 'claude CLI produced no stdout lines — cannot record a fixture from an empty round'
        String sessionId = extractSessionId(rawLines)
        String workspaceRootString = workspaceRoot.toString()
        List<String> scrubbed = rawLines.collect {
            ReferenceDumpScrubber.scrub(it, workspaceRootString, sessionId, scenarioLabel)
        }

        Path dumpFile = referenceDumpDir().resolve("${fixtureName}.reference.json")
        Files.write(dumpFile, scrubbed, StandardCharsets.UTF_8)
        true
    }

    private static String extractSessionId(List<String> rawLines) {
        for (String line : rawLines) {
            def matcher = line =~ /"session_id"\s*:\s*"([^"]+)"/
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return 'unknown-session'
    }

    private static Path referenceDumpDir() {
        String configured = System.getProperty('referenceDumpDir')
        assert configured != null && !configured.isBlank():
        'paidSmokeTest must set the referenceDumpDir system property (see build.gradle)'
        Path dir = Path.of(configured)
        assert Files.isDirectory(dir): "referenceDumpDir does not exist: ${dir}"
        dir
    }
}
