package com.github.oinsio.gnomish.e2e

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import spock.lang.Specification
import spock.lang.Timeout

/**
 * The reference E2E session (task 9.2): a single scripted stdin script drives a real
 * {@code gnomish run} process through every stage of the manual-run journey in one pass —
 * a quality-check failure that retries and later passes (burning one attempt), a
 * gnome-initiated decision escalation that resumes without burning an attempt, a manual
 * checkpoint once the stage's checks finally all pass, and completion at the pipeline's
 * end — asserting the process exits 0 and that the workspace gained nothing beyond the
 * stage manifest's own command artifact (no runner-created files leak into it).
 *
 * <p>The {@code e2e} fixture's {@code command} check (stage.yaml) is stateful: {@code
 * test -f attempt-marker.txt && exit 0 || { touch attempt-marker.txt; exit 1; }} run via
 * {@code sh -c} with the workspace as cwd — it fails and creates {@code attempt-marker.txt}
 * on its first invocation, then passes on every later one. That file is written by the
 * stage manifest's own command, not by the runner process — design D3 explicitly permits
 * the workspace mutating through "the operator and the manifest's own commands"; NFR-S1
 * forbids only files the *runner* itself creates (findings temp files, logs), which must
 * live outside the workspace. This spec tells the two apart by enumerating the workspace
 * tree before and after the run and asserting the only change is that one expected marker.
 *
 * <p>Implements M1, FR12, NFR-S1 of add-manual-run.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ReferenceE2ESessionSpec extends Specification {

    private final E2eProcessHarness harness = new E2eProcessHarness()
    private static final String MARKER_FILE = 'attempt-marker.txt'

    def cleanup() {
        // Reset the fixture to its pristine state so a re-run doesn't see a stale
        // already-passing command check from a prior invocation of this spec.
        Files.deleteIfExists(E2eFixture.projectRoot().resolve(MARKER_FILE))
    }

    def "M1: quality retry, decision escalation + resume, manual pause, and completion all exit 0 with no runner artifacts in the workspace"() {
        given: 'the workspace holds only its pristine fixture files before the run'
        Set<String> before = listWorkspaceRelative()

        and: 'a scripted session driving the full reference journey'
        List<String> script = [
            // --- Round 1 (attempt 0): gnome-initiated decision escalation ---
            'ask',
            // InteractiveStageExecutor: 'ask' opens the question/options dialog
            'should the fixture use approach A or B?',
            // the question
            'approach A',
            // option 1
            'approach B',
            // option 2
            '',
            // empty line ends the options list -> DecisionNeeded, no attempt burned
            'use approach A',
            // EscalationResumeDialog's decision prompt: non-empty decision, resumes attemptsUsed=0

            // --- Round 2 (attempt 0 again, after the escalation reset): quality failure ---
            '',
            // InteractiveStageExecutor: empty Enter -> Completed
            // verify chain: files_exist passes; the stateful command check fails on this
            // first real execution (creates attempt-marker.txt, exits 1) -> Verdict.Fail;
            // the chain short-circuits here (VerifyOrchestrator breaks at the first
            // non-Pass verdict), so external/judge are NOT prompted this round. attempt 0
            // is burned (attemptsUsed -> 1), stage retries (limit is 3).

            // --- Round 3 (attempt 1, the retry): all four checks pass ---
            '',
            // InteractiveStageExecutor: empty Enter -> Completed
            // files_exist passes; command check finds attempt-marker.txt -> passes
            'pass',
            // InteractiveExternalCheckClient poll verdict
            'pass',
            // InteractiveJudgeVoter vote (1 vote configured)
            // all checks pass -> stage passes -> advancement: manual -> Paused

            // --- Manual checkpoint ---
            ''
            // RunnerOutcomeLoop.handlePaused confirmation -> resumes past 'work' ->
            // Position.PipelineEnd -> Engine returns Completed immediately, no further
            // executor/verify round is run.
        ]

        when:
        def result = harness.run(
                E2eFixture.projectRoot(),
                [
                    '--project=' + E2eFixture.projectRoot(),
                    '--task=reference session: retry, escalate, pause, complete',
                    '--interactive'
                ],
                script)

        then: 'the process reaches Completed and exits 0 (FR12)'
        result.exitCode() == 0

        and: 'stdout shows the decision escalation was rendered'
        result.stdout().contains('The gnome asked: should the fixture use approach A or B?')
        result.stdout().contains('approach A')
        result.stdout().contains('approach B')

        and: 'stdout shows the resume decision prompt'
        result.stdout().contains('Decision (empty to resume without one)')

        and: 'stdout shows the manual checkpoint was reached'
        result.stdout().contains("Stage 'work' passed. Manual checkpoint reached.")

        and: 'stdout shows a final completion status'
        result.stdout().contains('Stage: pipeline complete')

        and: 'stderr carries no stack trace (UX3)'
        !result.stderr().contains('\tat ')

        and: 'NFR-S1: the only workspace change is the marker file the stage command wrote'
        Set<String> after = listWorkspaceRelative()
        after - before == [MARKER_FILE] as Set
        before - after == [] as Set
    }

    /** @return workspace-relative paths of every regular file under the fixture project root */
    private static Set<String> listWorkspaceRelative() {
        Path root = E2eFixture.projectRoot()
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map { root.relativize(it).toString() }
                    .collect(Collectors.toSet())
        }
    }
}
