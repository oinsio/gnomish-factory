package com.github.oinsio.gnomish.e2e

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Proves the E2E harness mechanics themselves work (task 9.1, M1): a real {@code
 * gnomish run} process, spawned via {@code java -jar}, driven through the {@code
 * e2e} fixture's single {@code work} stage with piped stdin, actually launches,
 * its stdout/stderr are captured, and an exit code comes back. This is NOT the
 * reference E2E scenario (task 9.2: quality retry, escalation, resume, pause,
 * completion) nor the exit-code matrix (task 9.3) — just proof the harness itself
 * functions, for those later specs to build on.
 *
 * <p>The scripted session answers every prompt the {@code work} stage's four
 * checks and its manual-checkpoint advancement can raise: empty Enter completes
 * the interactive executor round; {@code files_exist} passes automatically
 * against the fixture, but the {@code command} check is stateful (task 9.2's
 * {@code attempt-marker.txt} fixture) — it fails and writes its marker on the
 * first round, so this script answers a second empty-Enter round before the
 * check passes; {@code pass} answers the external check's single poll and the
 * one-vote judge check; a final empty Enter answers the manual-checkpoint
 * confirmation once the stage's checks all pass, after which the (only) stage
 * is done and the run completes.
 *
 * <p>M1 of add-manual-run.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class E2eProcessHarnessSmokeSpec extends Specification {

    private final E2eProcessHarness harness = new E2eProcessHarness()

    def cleanup() {
        // The stateful command check writes this marker into the fixture on its
        // first (failing) invocation — reset it so a re-run starts pristine.
        Files.deleteIfExists(E2eFixture.projectRoot().resolve('attempt-marker.txt'))
    }

    def "M1: a real gnomish run process launches, is driven by piped stdin, and returns an exit code"() {
        given: 'a scripted session answering the executor, external, and judge prompts, then the checkpoint'
        List<String> script = [
            '',
            // interactive StageExecutor round 1: empty Enter -> Completed; the stateful
            // command check fails here (creates attempt-marker.txt) and burns attempt 0
            '',
            // interactive StageExecutor round 2 (retry): empty Enter -> Completed; the
            // command check now finds the marker and passes
            'pass',
            // interactive ExternalCheckClient poll verdict
            'pass',
            // interactive JudgeVoter vote (1 vote configured)
            ''       // manual-checkpoint confirmation
        ]

        when:
        def result = harness.run(
                E2eFixture.projectRoot(),
                [
                    '--project=' + E2eFixture.projectRoot(),
                    '--task=smoke test the harness'
                ],
                script)

        then: 'the harness mechanics themselves work: a process ran and reported an exit code'
        result.exitCode() == 0

        and: 'stdout carries the dialog'
        result.stdout().contains('Press Enter when done')

        and: 'stderr carries no stack trace (UX3)'
        !result.stderr().contains('\tat ')
    }
}
