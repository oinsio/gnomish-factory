package com.github.oinsio.gnomish.e2e

import static com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages.round
import static com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages.taskEvent
import static com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent.APPROVED
import static com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent.COMPLETED
import static com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent.ESCALATED
import static com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent.PAUSED
import static com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent.RESUMED
import static com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent.STARTED

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.ServiceCommitMessages
import com.github.oinsio.gnomish.app.TerminalOutcomeRender
import java.nio.file.Path

/**
 * The reference E2E session (task 9.2 of add-manual-run, rewritten by task 4.1 of
 * make-run-headless): the fake agent as gnome and judge drives the real {@code gnomish run}
 * through every stop of the manual-run journey as <em>three processes, each with an empty,
 * closed stdin</em> (M3) — the shape an AI agent scripting {@code run} from the exit code alone
 * sees:
 *
 * <ol>
 *   <li>{@code run} — the gnome asks a decision; the run parks on the branch and exits 10 with the
 *       question and the return-path line (FR1, FR7);
 *   <li>{@code --resume --decision} — the return-path line process 1 printed, run as a shell
 *       would run it, with the decision filled in (task 4.5): the decision is recorded, the
 *       stateful command check fails once (a quality retry burning one attempt), the retry passes
 *       every check, the manual checkpoint parks the run and it exits 11 with the checkpoint line
 *       (FR3, FR2, FR7);
 *   <li>{@code --resume} — the line process 2 printed, run as printed: the paused task continues
 *       past the checkpoint to the pipeline's end and exits 0 with no confirmation asked (FR5).
 * </ol>
 *
 * <p>The fake agent plays {@code decision-then-plain} for the executor — a decision on its first
 * round, {@code plain-round} on every later one in the same worktree — and {@code
 * judge-verdict-pass} for the one-vote judge, selected by the judge check's {@code judge-model}
 * (D4 of remove-interactive-console). The {@code e2e} fixture's {@code command} check fails and
 * creates {@code attempt-marker.txt} on its first invocation, then passes.
 *
 * <p>Each stop is also asserted on the durable medium: the task branch on the bare origin gains, per
 * process, exactly the commits that stop implies — the park before the exit, the two rounds of the
 * quality retry, and — when the paused task completes — the approval commit that moves it past the
 * checkpoint (FR3, FR4 of make-checkpoint-gate-durable) and no round at all.
 *
 * <p>Git mode works in a worktree under the factory home, so the operator's clone must gain
 * nothing over the whole journey: no runner file and no gnome file leaks into it (NFR-S1 of
 * add-manual-run, restated for the mode that can resume).
 *
 * <p>Implements M3, FR1, FR2, FR3, FR5, FR7 of make-run-headless; M1, NFR-S1 of add-manual-run;
 * FR3, FR4 of make-checkpoint-gate-durable.
 */
class ReferenceE2ESessionSpec extends AbstractE2eProcessSpec {

    private static final String TASK = 'reference-session'

    def "M3: escalate, decide and resume, retry, pause, resume, complete — three processes with empty stdin exit 10, 11, 0"() {
        given: 'a published clone bound to the host, and the fake agent as gnome and judge'
        Path clone = E2eGitTree.publishedCopyOf('e2e')
        def agent = FakeAgentSupport.wrapperFor('decision-then-plain', 'judge-model', 'judge-verdict-pass')
        def returnPath = new TerminalOutcomeRender.ReturnPath(clone, TASK)

        when: 'process 1 runs the task with stdin closed'
        def first = harness.run(clone, agent,
                [
                    '--dir=' + clone,
                    '--task=reference session: escalate, retry, pause, complete',
                    '--task-id=' + TASK
                ], [])

        then: 'FR1, FR7: the gnome\'s question parks the run — exit 10, the question, then the return path with the optional decision'
        first.exitCode() == 10
        first.stdout().contains('The gnome asked:\nRefactor or patch?\nOptions:\nrefactor\npatch')
        first.stdout().contains(returnPath.line(true))
        !first.stdout().contains(TerminalOutcomeRender.checkpointLine('work'))

        and: 'FR1: the escalation is parked on the branch before the exit'
        pushedSubjects(clone) == [
            taskEvent(STARTED),
            round('work', 0),
            taskEvent(ESCALATED)
        ]

        when: 'process 2 runs the printed command with the decision filled in, stdin closed'
        def second = harness.run(clone, agent, printedResumeCommand(first.stdout()) + ['--decision=use approach A'], [])

        then: 'FR3, FR2, FR7: the checks pass on the retry and the manual checkpoint parks the run — exit 11, checkpoint line, return path'
        second.exitCode() == 11
        second.stdout().contains(TerminalOutcomeRender.checkpointLine('work') + '\n' + returnPath.line(false))

        and: 'the question is not asked again'
        !second.stdout().contains('The gnome asked:')

        and: 'the decision resume reset the attempts, round 0 failed the command check, the retry passed, and the pause is parked'
        pushedSubjects(clone).drop(3) == [
            taskEvent(RESUMED),
            round('work', 0),
            round('work', 1),
            taskEvent(PAUSED)
        ]

        when: 'process 3 runs the printed command as printed, stdin closed'
        def third = harness.run(clone, agent, printedResumeCommand(second.stdout()), [])

        then: 'FR5: the pipeline completes with no confirmation asked, exit 0'
        third.exitCode() == 0
        third.stdout().contains('Stage: pipeline complete')
        !third.stdout().contains(TerminalOutcomeRender.checkpointLine('work'))
        !third.stdout().contains(returnPath.line(false))

        and: 'FR3: the operator decision is on the task record'
        third.stdout().contains('use approach A (by operator) [stage: work]')

        and: 'FR5 (make-run-headless), FR4 (make-checkpoint-gate-durable): no round ran past the checkpoint — the approval moved the task past it, then it completed and cleaned up'
        pushedSubjects(clone).drop(7) == [
            taskEvent(APPROVED),
            taskEvent(COMPLETED),
            ServiceCommitMessages.cleanup()
        ]

        and: 'UX3: no process printed a stack trace'
        [first, second, third].every { !it.stderr().contains('\tat ') }

        and: 'NFR-S1: the operator\'s clone gained nothing over the journey'
        E2eGitTree.git(clone, 'status', '--porcelain', '--untracked-files=all') == ''
    }

    /**
     * The {@code gnomish run} arguments of the return-path line {@code stdout} printed, split the way
     * a POSIX shell splits a pasted command (task 4.5 of make-run-headless): the spec resumes with
     * what the operator is told to run, so a line the CLI cannot parse fails the journey here. The
     * optional {@code [--decision="..."]} hint is dropped; the caller appends a real decision.
     *
     * @return the arguments after {@code gnomish run}
     */
    private static List<String> printedResumeCommand(String stdout) {
        String line = stdout.readLines().find { it.startsWith('To continue: ') }
        assert line != null: stdout
        String command = (line - 'To continue: ') - ' [--decision="..."]'
        def shell = new ProcessBuilder('sh', '-c', "printf '%s\\0' " + command).start()
        List<String> words = new String(shell.inputStream.readAllBytes(), 'UTF-8').split('\u0000') as List<String>
        assert shell.waitFor() == 0
        assert words.take(2) == ['gnomish', 'run']: line
        words.drop(2)
    }

    /** @return the subjects of the task branch's commits on the bare origin, oldest first, above {@code main} */
    private static List<String> pushedSubjects(Path clone) {
        E2eGitTree.git(clone.resolveSibling('origin.git'), 'log', '--reverse', '--format=%s', '--branches', '--not', 'main')
                .readLines()
    }
}
