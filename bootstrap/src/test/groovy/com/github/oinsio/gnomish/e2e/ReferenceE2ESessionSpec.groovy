package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * The reference E2E session (task 9.2): the fake agent as gnome and judge, and stdin carrying
 * only the operator's answers, drive a real {@code gnomish run} process through every stage of
 * the manual-run journey in one pass — a gnome-initiated decision escalation that resumes
 * without burning an attempt, a quality-check failure that retries and later passes (burning
 * one attempt), a manual checkpoint once the stage's checks finally all pass, and completion at
 * the pipeline's end — asserting the process exits 0 and that the workspace gained nothing
 * beyond the gnome's own files and the stage manifest's own command artifact (no runner-created
 * files leak into it).
 *
 * <p>The fake agent plays {@code decision-then-plain} for the executor — a decision on its first
 * round, {@code plain-round} on every later one — and {@code judge-verdict-pass} for the one-vote
 * judge, selected by the judge check's {@code judge-model} (D4 of remove-interactive-console).
 *
 * <p>The {@code e2e} fixture's {@code command} check (stage.yaml) is stateful: {@code
 * test -f attempt-marker.txt && exit 0 || { touch attempt-marker.txt; exit 1; }} run via
 * {@code sh -c} with the workspace as cwd — it fails and creates {@code attempt-marker.txt}
 * on its first invocation, then passes on every later one. That file is written by the
 * stage manifest's own command, not by the runner process — design D3 explicitly permits
 * the workspace mutating through "the operator and the manifest's own commands"; NFR-S1
 * forbids only files the *runner* itself creates (findings temp files, logs), which must
 * live outside the workspace. The gnome's own writes ({@link #FAKE_AGENT_FILES}) are likewise
 * not the runner's. This spec tells them apart by enumerating the workspace tree before and
 * after the run and asserting the only changes are those expected files.
 *
 * <p>Implements M1, FR12, NFR-S1 of add-manual-run; FR6 of remove-interactive-console.
 */
class ReferenceE2ESessionSpec extends AbstractE2eProcessSpec {

    def "M1: decision escalation + resume, quality retry, manual pause, and completion all exit 0 with no runner artifacts in the workspace"() {
        given: 'the workspace holds only its pristine fixture files before the run'
        Set<String> before = listWorkspaceRelative()

        and: 'the fake agent as gnome and judge'
        def agent = FakeAgentSupport.wrapperFor('decision-then-plain', 'judge-model', 'judge-verdict-pass')

        and: 'stdin carrying only the operator answers of the reference journey'
        List<String> script = [
            // --- Round 1 (attempt 0): the fake agent asks a decision -> DecisionNeeded, no attempt burned ---
            'use approach A',
            // EscalationResumeDialog's decision prompt: non-empty decision, resumes attemptsUsed=0

            // --- Round 2 (attempt 0 again, after the escalation reset): quality failure ---
            // plain-round -> Completed; files_exist passes; the stateful command check fails on
            // this first real execution (creates attempt-marker.txt, exits 1) -> Verdict.Fail;
            // the chain short-circuits here (VerifyOrchestrator breaks at the first non-Pass
            // verdict), so the judge is NOT asked this round. attempt 0 is burned
            // (attemptsUsed -> 1), stage retries (limit is 3).

            // --- Round 3 (attempt 1, the retry): all three checks pass ---
            // plain-round -> Completed; files_exist passes; command check finds
            // attempt-marker.txt -> passes; judge-verdict-pass (1 vote configured)
            // -> stage passes -> advancement: manual -> Paused

            // --- Manual checkpoint ---
            ''
            // RunnerOutcomeLoop.handlePaused confirmation -> resumes past 'work' ->
            // Position.PipelineEnd -> Engine returns Completed immediately, no further
            // executor/verify round is run.
        ]

        when:
        def result = harness.run(
                E2eFixture.projectRoot(),
                agent,
                [
                    '--dir=' + E2eFixture.projectRoot(),
                    '--task=reference session: escalate, retry, pause, complete',
                    '--mode=in-place'
                ],
                script)

        then: 'the process reaches Completed and exits 0 (FR12)'
        result.exitCode() == 0

        and: 'stdout shows the decision escalation was rendered'
        result.stdout().contains('The gnome asked:')
        result.stdout().contains('Refactor or patch?')
        result.stdout().contains('refactor')
        result.stdout().contains('patch')

        and: 'stdout shows the resume decision prompt'
        result.stdout().contains('Decision (empty to resume without one)')

        and: 'stdout shows the manual checkpoint was reached'
        result.stdout().contains("Stage 'work' passed. Manual checkpoint reached.")

        and: 'stdout shows a final completion status'
        result.stdout().contains('Stage: pipeline complete')

        and: 'stderr carries no stack trace (UX3)'
        !result.stderr().contains('\tat ')

        and: 'NFR-S1: the only workspace changes are the stage command\'s marker and the gnome\'s own files'
        Set<String> after = listWorkspaceRelative()
        after - before == ([MARKER_FILE] + FAKE_AGENT_FILES) as Set
        before - after == [] as Set
    }

    /** @return workspace-relative paths of every regular file under the fixture project root */
    private static Set<String> listWorkspaceRelative() {
        Path root = E2eFixture.projectRoot()
        try (var stream = Files.walk(root)) {
            return stream
                    .filter { Files.isRegularFile(it) }
                    .map { root.relativize(it).toString() }
                    .collect(Collectors.toSet())
        }
    }
}
