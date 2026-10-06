package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.app.TerminalOutcomeRender
import java.nio.file.Files
import java.nio.file.Path

/**
 * The outcome rows of the exit-code matrix (split from {@link ExitCodeMatrixSpec} by task 4.1 of
 * make-run-headless, which rewrote them): exit codes 10 and 11 are derived from the terminal
 * outcome the run stopped on, never from an end of input — every process here runs with stdin
 * closed from the start, so nothing could be read even if something tried.
 *
 * <ul>
 *   <li>an escalation in in-place mode &rarr; 10, the report and no return path (no branch);
 *   <li>a manual checkpoint in in-place mode &rarr; 11, the checkpoint line and no return path;
 *   <li>an unanswered {@code DecisionNeeded} resumed without {@code --decision} &rarr; 10 again,
 *       the question restated with the return path, and no round run.
 * </ul>
 *
 * <p>Implements FR1, FR2, FR4, FR7 of make-run-headless.
 */
class ParkExitCodeSpec extends AbstractE2eProcessSpec {

    def "FR1, FR7: an escalation with stdin closed exits 10 by outcome, with no return path in in-place mode"() {
        given: 'the fake agent asks a decision on its first round'
        def agent = FakeAgentSupport.wrapperFor('decision-needed')

        when:
        def result = harness.run(E2eFixture.projectRoot(), agent, [
            '--dir=' + E2eFixture.projectRoot(),
            '--task=escalation exit',
            '--mode=in-place'
        ], [])

        then: 'FR7: the DecisionNeeded outcome maps to exit 10'
        result.exitCode() == 10

        and: 'FR1: the report is rendered; in-place mode has no branch, so no resume command is offered'
        result.stdout().contains('The gnome asked:\nRefactor or patch?\nOptions:\nrefactor\npatch')
        !result.stdout().contains('To continue:')
    }

    def "FR2, FR7: a manual checkpoint with stdin closed exits 11 by outcome, with no return path in in-place mode"() {
        given: 'the stateful command check pre-passes, so the stage passes on its first attempt'
        Files.writeString(E2eFixture.projectRoot().resolve(MARKER_FILE), '')

        and: 'the fake agent plays a clean round and a passing judge vote'
        def agent = FakeAgentSupport.wrapperFor('plain-round', 'judge-model', 'judge-verdict-pass')

        when:
        def result = harness.run(E2eFixture.projectRoot(), agent, [
            '--dir=' + E2eFixture.projectRoot(),
            '--task=checkpoint exit',
            '--mode=in-place'
        ], [])

        then: 'FR7: the Paused outcome maps to exit 11'
        result.exitCode() == 11

        and: 'FR2: the checkpoint line names the passed stage, and no resume command is offered'
        result.stdout().contains(TerminalOutcomeRender.checkpointLine('work'))
        !result.stdout().contains('To continue:')
    }

    def "FR4: an unanswered DecisionNeeded resumed without --decision exits 10 again with the question restated and no round run"() {
        given: 'a git-mode run parked on the gnome\'s question'
        Path clone = E2eGitTree.publishedCopyOf('e2e')
        def agent = FakeAgentSupport.wrapperFor('decision-needed')
        def returnPath = new TerminalOutcomeRender.ReturnPath(clone, 'unanswered')
        def parked = harness.run(clone, agent, [
            '--dir=' + clone,
            '--task=unanswered question',
            '--task-id=unanswered'
        ], [])
        assert parked.exitCode() == 10: parked.stdout() + parked.stderr()
        String parkedTip = originTaskTip(clone)

        when: 'the task is resumed with no decision, stdin closed'
        def result = harness.run(clone, agent, [
            '--dir=' + clone,
            '--resume=unanswered'
        ], [])

        then: 'exit 10 again'
        result.exitCode() == 10

        and: 'the question and its options are restated, followed by the return path'
        result.stdout().contains('The gnome asked:\nRefactor or patch?\nOptions:\nrefactor\npatch\n' + returnPath.line(true))

        and: 'no round ran and no attempt was burned: the branch on origin is exactly the parked one'
        originTaskTip(clone) == parkedTip

        and: 'no stack trace'
        !result.stderr().contains('\tat ')
    }

    /** @return the commit the bare origin's only task branch points at */
    private static String originTaskTip(Path clone) {
        E2eGitTree.git(clone.resolveSibling('origin.git'), 'rev-parse', '--branches=gnomish/*')
    }
}
