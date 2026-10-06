package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport

/**
 * Proves the E2E harness mechanics themselves work (task 9.1, M1): a real {@code
 * gnomish run} process, spawned via {@code java -jar}, driven through the {@code
 * e2e} fixture's single {@code work} stage with the fake agent as its gnome and judge,
 * actually launches, its stdout/stderr are captured, and an exit code comes back. This is
 * NOT the reference E2E scenario (task 9.2: quality retry, escalation, resume, pause,
 * completion) nor the exit-code matrix (task 9.3) — just proof the harness itself
 * functions, for those later specs to build on.
 *
 * <p>The fake agent plays {@code plain-round} for every executor round and
 * {@code judge-verdict-pass} for the one-vote judge, selected by the judge check's
 * {@code judge-model} (D4 of remove-interactive-console). {@code files_exist} passes
 * against the fixture; the {@code command} check is stateful (task 9.2's
 * {@code attempt-marker.txt} fixture) — it fails and writes its marker on the first round,
 * so a second round runs before it passes; the judge then passes, and the run stops at the
 * stage's manual checkpoint with exit 11 — in-place, so there is nothing to resume (FR2 of
 * make-run-headless).
 *
 * <p>M1 of add-manual-run; FR6 of remove-interactive-console; FR2, FR7 of make-run-headless.
 */
class E2eProcessHarnessSmokeSpec extends AbstractE2eProcessSpec {

    def "M1: a real gnomish run process launches, is driven by the fake agent, and returns an exit code"() {
        given: 'the fake agent as gnome and judge, and no stdin at all'
        def agent = FakeAgentSupport.wrapperFor('plain-round', 'judge-model', 'judge-verdict-pass')
        List<String> script = []

        when:
        def result = harness.run(
                E2eFixture.projectRoot(),
                agent,
                [
                    '--dir=' + E2eFixture.projectRoot(),
                    '--task=smoke test the harness',
                    '--mode=in-place'
                ],
                script)

        then: 'FR6: run\'s parser passes the --factory.agent-cli-binary argument through'
        !result.stderr().contains('unknown option')

        and: 'the harness mechanics themselves work: a process ran and reported the checkpoint exit code'
        result.exitCode() == 11

        and: 'stdout carries the checkpoint the fake-agent rounds and the judge vote led to'
        result.stdout().contains("Stage 'work' passed. Manual checkpoint reached.")

        and: 'stderr carries no stack trace (UX3)'
        !result.stderr().contains('\tat ')
    }
}
