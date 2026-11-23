package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.agent.program.AnswerOutcome
import com.github.oinsio.gnomish.adapter.check.SandboxCheckEnvironmentSource
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCheckRounds
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.EnvironmentLease
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files

/**
 * FR1, NFR-S3 of add-sandbox-core over a real sandboxed round (the engine, the round adapters, a
 * box over a real clone): what crosses the factory↔environment boundary, and when.
 *
 * <ul>
 *   <li>The file channel is used between rounds only — never while a process runs in the box.
 *   <li>Content the gnome leaves for the factory leaves it only as sanitized, capped excerpts.
 *   <li>The commit a fresh box is pinned at is the factory's own resolution of the harvested tip,
 *       whatever the box does after.
 * </ul>
 */
class SandboxRoundChannelSpec extends RoundFlowSpecBase {

    // Scenario: sandbox/execution-environment / File operations run only between rounds
    def "FR1: every file the factory exchanges with the box is read or written while no process runs there"() {
        given: 'a round in which the gnome asks a question, then the engine records the round'
        def box = localBox()

        when:
        def outcome = run(pipeline(3), box, 'decision-needed', new ScriptedCheckRounds())

        then: 'the round reached the factory: the question was read and the round committed'
        outcome instanceof TaskOutcome.Escalated
        box.channelCalls*.operation.containsAll(['readFile', 'putFile'])

        and: 'each of those exchanges happened with nothing running in the box'
        box.channelCalls.every { it.running() == 0 }
    }

    // Scenario: sandbox/execution-environment / Environment content reaches logs only as sanitized excerpts
    // Scenario: observability/factory-logging / Unbounded payload is capped
    // FR20 of extract-agent-round-runner (design D15, part 3): a transform round's unparseable answer
    //     file is the round's quality failure, which the engine answers with the next attempt and
    //     writes no operator line for (logging.md, "One failure, one log"); the gnome's text leaves the
    //     factory as the round's one finding — the cause in the factory's words quoting an excerpt of
    //     what was written, one inert, capped line — and reaches no log line as written
    def "NFR-S3: a hostile file the gnome leaves for the factory leaves it only as inert, capped excerpts"() {
        given: 'the gnome writes an answer file carrying line breaks, terminal escapes and a forged log line, far past a line'
        def hostile = ('x' * 5_000) + '\nnot json 2026-08-31 12:00:00 ERROR forged\u001B[31mred\u001B[0m\u2029tail'
        def box = localBox()
        box.atAgentLaunch = { command ->
            def file = box.box.workingCopy.resolve(command.env()['GNOMISH_ANSWER_FILE'] as String)
            Files.createDirectories(file.parent)
            Files.writeString(file, hostile)
        }

        when:
        def logs = LogCaptureSupport.attach(org.slf4j.Logger.ROOT_LOGGER_NAME, Level.WARN)
        def outcome = run(pipeline(1), box, 'plain-round', new ScriptedCheckRounds())
        def lines = List.copyOf(logs.list)
        logs.detach()

        then: 'the factory read it from the box and refused it: the round\'s quality failure used the stage\'s one attempt'
        (outcome as TaskOutcome.Escalated).report() instanceof EscalationReport.AttemptsExhausted
        def findings = (outcome.finalState().attempts()[0].checkResults()[0].verdict() as Verdict.Fail).findings()
        findings.size() == 1

        and: 'and quotes what was written as one inert excerpt, within the excerpt bound, its end kept'
        def excerpt = findings[0].details()
        excerpt.contains('x' * 100)
        excerpt.endsWith('tail')
        excerpt != hostile
        excerpt.length() <= AnswerOutcome.EXCERPT_CHARS
        !excerpt.contains('\n') && !excerpt.contains('\u2029') && !excerpt.contains('\u001B')

        and: 'the finding names the cause and quotes that excerpt, in one line capped by the excerpt bound'
        def cause = findings[0].message()
        cause.startsWith("agent executor 'claude': wrote a malformed answer file: ")
        cause.endsWith('; it wrote: ' + excerpt)
        cause.length() - excerpt.length() < 1_000
        !cause.contains('\n') && !cause.contains('\u2029') && !cause.contains('\u001B')

        and: 'no operator line carries the text as written'
        lines.every {
            def line = it.formattedMessage
            !line.contains(hostile) && !line.contains('\u001B') && !line.contains('\u2029')
        }
    }

    // Scenario: sandbox/execution-environment / The materialize pin is chosen by the factory
    def "FR1: a judge request's fresh box and a fresh-box check are pinned at the tip the factory resolved, not at the box's HEAD"() {
        given: 'a round that ended in a failed check: its snapshot harvested into the factory clone, the state commit on top'
        def box = localBox()
        run(pipeline(1), box, 'plain-round', ScriptedCheckRounds.fixed(new Verdict.Fail([])))
        def harvested = gitOutput(clone, 'rev-parse', tip() + '^')
        assert subjectOf(clone, harvested).startsWith('gnomish: snapshot build#0')

        and: 'the gnome then moves HEAD in the box and leaves a commit id of its own choosing in the working copy'
        def workingCopy = box.box.workingCopy
        Files.writeString(workingCopy.resolve('later.txt'), 'after the harvest')
        gitOutput(workingCopy, 'add', '-A')
        gitOutput(workingCopy, 'commit', '-q', '-m', 'in-box only')
        Files.writeString(workingCopy.resolve('PIN'), gitOutput(workingCopy, 'rev-parse', 'HEAD'))

        // A judge request's box is a fresh box at the workspace's attempt commit (design D2 of
        //     extract-agent-round-runner): its binding reads the commit as below.
        when: 'a judge request\'s fresh box and the fresh-box check source build their boxes'
        def pins = []
        def recording = {
            ->
            [materialize: { String branch, String pin ->
                    pins << pin
                }, dispose: { -> }] as TaskExecutionEnvironment
        }
        def workspace = new RecordedAttemptCommitWorkspace(rounds)
        def source = new SandboxCheckEnvironmentSource(new EnvironmentLease({
            -> box
        }, BRANCH, []), recording, { List<String> grant ->
            recording()
        }, BRANCH)
        source.freshBox(workspace.attemptCommitSha(), [])
        source.acquire(VerifyChecks.command('make check', VerifyCheck.VerifyIn.FRESH_BOX), workspace)

        then: 'both are pinned at the factory\'s resolution of the harvested tip'
        pins == [harvested, harvested]
        pins.every { it != gitOutput(workingCopy, 'rev-parse', 'HEAD') }
    }
}
