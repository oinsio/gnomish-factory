package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.app.HostVerificationKill
import com.github.oinsio.gnomish.app.project.ProjectRegistry
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.PendingFeature
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Task 7.2 of extract-agent-round-runner: the packaged factory ({@code java -jar … run}, host git
 * mode, a bare origin) drives the fake agent binary through the Claude dialect of the {@code claude}
 * registry entry — a {@code transform} round and one judge verdict request (the judge check's own
 * {@code executor:} block, design D2, D12, D13), a decision request answered and continued by a
 * second instance on another clone from the branch record alone (FR9, D4 as amended 2026-11-21: the
 * question, its options and the reply on the next round's stdin, no session flag on any argv), the
 * round line naming {@code executor=claude@built-in} (NFR-O1; {@code run} has no ledger, its round
 * line is {@code LoggingEventListener}'s {@code execution finished}; pending on finding F1 of task
 * 7.2), and an invalid answer burning one attempt whose finding reaches the next round's stdin (FR20,
 * D15).
 *
 * <p>The agent ({@link ClaudeAgentCapture}) is the committed preset {@code [agent-judged-capturing-round]}
 * behind a per-run link named after the scenario it plays: an invocation with {@code --model judge-model} plays {@code
 * judge-verdict-pass}, every prompt lands in the link's {@code .log}, every argv in its {@code .argv}.
 * What the processes did is read from those captures, the origin and each clone's project log.
 *
 * <p>{@link ReferenceE2ESessionSpec} drives the same journey's stops on one clone; the features here
 * are new: the second instance, the captured prompt and argv, the executor's name on the round line,
 * the invalid answer.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class ClaudeRoundJarE2eSpec extends Specification {

    static final String REPLY = 'refactor, keep the public API'
    static final List<String> SESSION_FLAGS = [
        '--resume',
        '--continue',
        '--session-id',
        '-r',
        '-c'
    ]
    static final String ROUND_LINE = 'execution finished: executor=claude@built-in, '

    private final E2eProcessHarness harness = new E2eProcessHarness()
    private final String task = "e2e-claude-${System.nanoTime() % 1_000_000}"

    // Scenario: executor/agent-executor / Round through the registry
    def "FR9: a decision request is answered, and a second instance's fresh round is told the question, its options and the reply, with no session flag"() {
        given: 'a published clone bound to the host'
        Path clone = E2eGitTree.publishedCopyOf('e2e-claude')
        def asking = ClaudeAgentCapture.playing('decision-then-plain')

        when: 'the first instance runs the task and the gnome asks'
        def first = harness.run(clone, asking.toString(), [
            '--dir=' + clone,
            '--task=decide, then do the work',
            '--task-id=' + task
        ], [])

        then: 'the run parks on the question: exit 10'
        first.exitCode() == 10
        first.stdout().contains('The gnome asked:\nRefactor or patch?\nOptions:\nrefactor\npatch')

        when: 'a second instance on another clone of origin answers and continues the task'
        Path other = otherClone(clone)
        def working = ClaudeAgentCapture.playing('plain-round')
        def second = harness.run(other, working.toString(), [
            '--dir=' + other,
            '--resume=' + task,
            '--decision=' + REPLY
        ], [])

        then: 'the pipeline completed on the second instance'
        completed(second)

        and: 'its first round was told the question, its options and the reply the decision records'
        List<String> prompts = working.prompts()
        prompts.size() == 2
        prompts[0].contains('- question: Refactor or patch?\n')
        prompts[0].contains('    * refactor\n')
        prompts[0].contains('    * patch\n')
        prompts[0].contains("reply (operator): ${REPLY}\n")

        and: 'one transform round and one judge verdict request ran, and no invocation carried a session flag'
        def launched = working.argvs()
        launched.size() == 2
        working.judged().count(true) == 1
        (launched + asking.argvs()).every { argv ->
            argv.every {
                !(it in SESSION_FLAGS)
            }
        }

        and: 'each instance wrote one transform round line'
        roundLines(clone).size() == 1
        roundLines(other).size() == 1
        (roundLines(clone) + roundLines(other)).every {
            it.contains('role=transform, ')
        }
    }

    // Scenario: executor/agent-round-runner / An unparseable answer file burns one attempt
    def "FR20: an invalid answer burns one attempt, and the next round's prompt carries the finding"() {
        given:
        Path clone = E2eGitTree.publishedCopyOf('e2e-claude')
        def garbling = ClaudeAgentCapture.playing('garbage-then-plain')

        when:
        def run = harness.run(clone, garbling.toString(), [
            '--dir=' + clone,
            '--task=answer badly, then do the work',
            '--task-id=' + task
        ], [])

        then: 'the pipeline completed after two rounds and one judge verdict request'
        completed(run)
        garbling.judged() == [false, false, true]

        and: 'the second round is a rework carrying the finding, which quotes what the gnome wrote'
        List<String> prompts = garbling.prompts()
        !prompts[0].contains("agent executor 'claude'")
        prompts[1].contains('=== Rework, not restart ===')
        String feedback = prompts[1].substring(prompts[1].indexOf('=== Prior-attempt feedback ==='))
        feedback.readLines()[2].startsWith("    * agent executor 'claude': wrote a malformed answer file: ")
        feedback.readLines()[2].contains('; it wrote: ' + getClass().getResource('/fake-agent/scenarios/garbage-then-plain/decision.json').text.trim())

        and: 'origin records the burned attempt and the passing one'
        Path origin = clone.resolveSibling('origin.git')
        String round = E2eGitTree.git(origin, 'log', '--format=%H', '--grep', '^gnomish: round work#1$', "refs/heads/gnomish/${task}")
        HostVerificationKill.stateAt(origin, round).attempts()*.result() == [
            AttemptRecord.Result.QUALITY_FAILURE,
            AttemptRecord.Result.PASSED
        ]

        and: 'both rounds wrote a transform round line'
        roundLines(clone).size() == 2
    }

    // NFR-O1: the round line of an agent block names the registry entry it resolved to, with its describe version
    @PendingFeature(reason = 'F1 of task 7.2: DescribedExecutors.executorOf names an agent block by its type word (agent-cli) with no version')
    def "NFR-O1: the round line of an agent-cli stage names executor=claude@built-in, role transform"() {
        given:
        Path clone = E2eGitTree.publishedCopyOf('e2e-claude')

        when:
        def run = harness.run(clone, ClaudeAgentCapture.playing('plain-round').toString(), [
            '--dir=' + clone,
            '--task=do the work',
            '--task-id=' + task
        ], [])

        then:
        completed(run)
        def lines = roundLines(clone)
        lines.size() == 1
        lines.every {
            it.contains(ROUND_LINE + 'protocolVersion=1, role=transform, ')
        }
    }

    /** Another instance's host: a fresh clone of {@code clone}'s origin, bound to the host. */
    private static Path otherClone(Path clone) {
        Path other = Files.createTempDirectory('gnomish-e2e-other').resolve('e2e-claude')
        E2eGitTree.git(other.parent, 'clone', '--quiet', clone.resolveSibling('origin.git').toString(), other.toString())
        E2eProcessHarness.projectConfig(other, 'factory:\n  bindings:\n    default: host\n')
        other
    }

    /** The round lines in {@code clone}'s project log: {@code run}'s view of the ledger. */
    private static List<String> roundLines(Path clone) {
        Path log = ProjectRegistry.scan(E2eProcessHarness.HOME).resolve(clone.toAbsolutePath().normalize()).layout().logFile('default')
        Files.readAllLines(log).findAll {
            it.contains('execution finished: ')
        }
    }

    private static boolean completed(E2eProcessResult result) {
        assert result.exitCode() == 0: result
        assert result.stdout().contains('Stage: pipeline complete')
        assert !result.stderr().contains('\tat ')
        true
    }
}
