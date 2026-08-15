package com.github.oinsio.gnomish.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The exit-code matrix (task 9.3): five scenarios pinning FR12's exit-code table and
 * FR13's EOF semantics against the real {@code gnomish run} process, complementing the
 * reference journey ({@link ReferenceE2ESessionSpec}, exit 0) and the harness smoke test
 * ({@link E2eProcessHarnessSmokeSpec}). {@code Aborted} (exit 12) needs a breaking
 * persistence fake and is covered elsewhere (in-process, not here).
 *
 * <ul>
 *   <li>usage error (bad flags) &rarr; 2
 *   <li>broken pipeline ({@code .gnomish/} invalid) &rarr; 3
 *   <li>truncated script (EOF mid-stage, Case 1) &rarr; 4
 *   <li>Ctrl-D at the escalation resume prompt (Case 2) &rarr; 10
 *   <li>Ctrl-D at the manual checkpoint prompt (Case 2) &rarr; 11
 * </ul>
 *
 * <p>Implements M1, FR12, FR13 of add-manual-run.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ExitCodeMatrixSpec extends Specification {

    private final E2eProcessHarness harness = new E2eProcessHarness()
    private static final String MARKER_FILE = 'attempt-marker.txt'

    def cleanup() {
        // Keep the shared e2e fixture pristine for other specs (ReferenceE2ESessionSpec,
        // E2eProcessHarnessSmokeSpec) that assume no stale command-check marker.
        Files.deleteIfExists(E2eFixture.projectRoot().resolve(MARKER_FILE))
    }

    def "usage error exits 2 without any dialog"() {
        given: 'neither --task nor --task-file is supplied'
        List<String> noArgsNeeded = []

        when:
        def result = harness.run(
                E2eFixture.projectRoot(),
                [
                    '--dir=' + E2eFixture.projectRoot()
                ],
                noArgsNeeded)

        then: 'FR12: usage error exit code'
        result.exitCode() == 2

        and: 'the message names the missing/conflicting flag'
        (result.stdout() + result.stderr()).contains('--task')
    }

    def "broken pipeline exits 3 before any dialog"() {
        given: 'a fixture whose plan stage references a missing instructions.md'
        Path brokenRoot = brokenFixtureRoot()

        when:
        def result = harness.run(
                brokenRoot,
                [
                    '--dir=' + brokenRoot,
                    '--task=irrelevant, load fails first',
                    '--mode=in-place'
                ],
                [])

        then: 'FR12: pipeline-load-failure exit code'
        result.exitCode() == 3

        and: 'the loader errors are printed as-is (no prompt reached)'
        (result.stdout() + result.stderr()).contains('instructions.md')
    }

    def "truncated script exits 4 without a resume-decision prompt"() {
        given: 'stdin closes immediately, before the executor prompt is answered'
        List<String> emptyScript = []

        when:
        def result = harness.run(
                E2eFixture.projectRoot(),
                [
                    '--dir=' + E2eFixture.projectRoot(),
                    '--task=script too short',
                    '--mode=in-place',
                    '--interactive'
                ],
                emptyScript)

        then: 'FR13: exit code 4, Case 1 short-circuit'
        result.exitCode() == 4

        and: 'the resume-decision dialog was never reached (NFR-R1)'
        !result.stdout().contains('Decision (empty to resume without one)')
    }

    def "Ctrl-D at the escalation resume prompt exits 10"() {
        given: 'scripted input reaches the DecisionNeeded escalation, then stdin closes'
        List<String> script = [
            'ask',
            'should the fixture use approach A or B?',
            'approach A',
            'approach B',
            ''
            // empty line ends the options list -> DecisionNeeded escalation, no attempt
            // burned; the engine has not touched the console since this last line, so
            // console.inputExhausted() is still false here. Stdin now closes with no
            // further lines: the escalation renders, then the resume-decision prompt
            // hits a fresh EOF (Case 2) -> EscalationEofException -> exit 10.
        ]

        when:
        def result = harness.run(
                E2eFixture.projectRoot(),
                [
                    '--dir=' + E2eFixture.projectRoot(),
                    '--task=ctrl-d at resume',
                    '--mode=in-place',
                    '--interactive'
                ],
                script)

        then: 'FR13: exit code 10'
        result.exitCode() == 10

        and: 'the escalation was rendered before the process exited'
        result.stdout().contains('The gnome asked: should the fixture use approach A or B?')
        result.stdout().contains('approach A')
        result.stdout().contains('approach B')
    }

    def "Ctrl-D at the manual checkpoint prompt exits 11"() {
        given: 'the stateful command check pre-passes so the stage completes on the first attempt'
        Files.writeString(E2eFixture.projectRoot().resolve(MARKER_FILE), '')

        and: 'scripted input completes the single round and all four checks, then stdin closes'
        List<String> script = [
            '',
            // InteractiveStageExecutor: empty Enter -> Completed
            'pass',
            // InteractiveExternalCheckClient poll verdict
            'pass'
            // InteractiveJudgeVoter vote (1 vote configured); files_exist and command both
            // pass -> stage passes -> advancement: manual -> Paused. Stdin now closes: the
            // checkpoint confirmation prompt hits a fresh EOF (Case 2) ->
            // CheckpointEofException -> exit 11.
        ]

        when:
        def result = harness.run(
                E2eFixture.projectRoot(),
                [
                    '--dir=' + E2eFixture.projectRoot(),
                    '--task=ctrl-d at checkpoint',
                    '--mode=in-place',
                    '--interactive'
                ],
                script)

        then: 'FR13: exit code 11'
        result.exitCode() == 11

        and: 'the checkpoint was reached before the process exited'
        result.stdout().contains("Stage 'work' passed. Manual checkpoint reached.")
    }

    /** @return the {@code e2e-broken} fixture root, resolved from the test classpath */
    private static Path brokenFixtureRoot() {
        Path.of(ExitCodeMatrixSpec.getResource('/.gnomish-fixtures/e2e-broken').toURI())
    }
}
