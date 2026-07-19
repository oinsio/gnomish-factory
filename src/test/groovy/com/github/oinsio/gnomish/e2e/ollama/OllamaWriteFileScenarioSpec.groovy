package com.github.oinsio.gnomish.e2e.ollama

import com.github.oinsio.gnomish.e2e.E2eProcessHarness
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The substantive Ollama E2E scenario (task 11.2, M1, D11): «agent creates a file → judge issues
 * a verdict» through a real {@code gnomish run} process — manifest-driven, no {@code
 * --interactive} flag, so both the stage executor and the judge check bind to the real CLI
 * adapters (FR10 of add-agent-executor; flagless run is the manifest-driven default). The fixture
 * ({@link OllamaFixture}) is deliberately trivial by design (D11's risk note): one stage,
 * instructed to create {@code hello.txt}; one judge vote whose only acceptance criterion is that
 * the file exists. A weak local Ollama model failing a harder task would read as an adapter bug
 * rather than a model-capability gap — trivial by construction rules that out.
 *
 * <p>This spec needs a reachable local Ollama and a {@code claude} CLI on {@code PATH}; both are
 * absent on most machines (CI included), so the scenario feature below skips cleanly via {@link
 * OllamaAvailability#harnessReady()} rather than failing the build — the expected outcome for
 * this task in an environment without Ollama installed. What is provable without a live Ollama —
 * fixture manifest validity, plumbing wiring, the skip path itself — is covered by {@link
 * OllamaFixtureLoadSpec} and {@link OllamaHarnessSmokeSpec}.
 *
 * <p>No assertion here presumes the local model actually succeeds: {@code gnomish run} exiting 0
 * would mean the stage passed (agent wrote the file, the judge voted pass) since the fixture's
 * {@code attemptLimit} is 2 and any quality failure would retry then escalate — reaching {@code
 * Completed}/exit 0 is only possible via a passing verdict. A weak or uncooperative model may
 * instead escalate (non-zero exit, an "unresolved" summary) or hang until the harness's own
 * timeout; both are reported as real outcomes, not asserted away, because compliance doubts are
 * resolved against the paid smoke task (11.3), not by loosening this spec (D11 risk note).
 *
 * <p>Implements M1, D11 of add-agent-executor.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class OllamaWriteFileScenarioSpec extends Specification {

    private final E2eProcessHarness harness = new E2eProcessHarness()

    def cleanup() {
        Files.deleteIfExists(OllamaFixture.expectedFile())
    }

    @IgnoreIf(
    value = { !OllamaAvailability.harnessReady() },
    reason = 'local Ollama unreachable or `claude` CLI not on PATH — see OllamaAvailability; this is expected outside a developer machine with Ollama installed (D11)')
    def "M1: a real agent-cli round creates hello.txt and the real judge verdict drives the stage to completion"() {
        given: 'the fixture workspace has no hello.txt before the run'
        assert !Files.exists(OllamaFixture.expectedFile())

        when: 'gnomish run drives the fixture manifest-driven, no --interactive override, against local Ollama'
        def result = harness.run(
                OllamaFixture.projectRoot(),
                [
                    '--dir=' + OllamaFixture.projectRoot(),
                    '--task=create hello.txt with any single line of text',
                    '--mode=in-place'
                ],
                [],
                false,
                OllamaEnv.forLocalOllama())

        then: 'the process does not crash — no stack trace on stderr regardless of the model outcome'
        !result.stderr().contains('\tat ')

        and: 'stdout shows the real CLI executor round and the real judge round both ran (not the interactive dialogs)'
        !result.stdout().contains('The gnome asked:')

        and: 'a passing run (exit 0) can only be reached via the file existing and a real passing verdict'
        result.exitCode() != 0 || Files.exists(OllamaFixture.expectedFile())
    }
}
