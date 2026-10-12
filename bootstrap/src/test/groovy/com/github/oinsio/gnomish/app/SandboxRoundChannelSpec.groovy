package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.agent.DecisionFileReader
import com.github.oinsio.gnomish.adapter.agent.FreshJudgeEnvironments
import com.github.oinsio.gnomish.adapter.check.SandboxCheckEnvironmentSource
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
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
 *   <li>Content the gnome leaves for the factory reaches the operator log as one sanitized, capped
 *       line.
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
        def outcome = run(pipeline(3), box, 'decision-needed', new ScriptedCommandCheckRunner())

        then: 'the round reached the factory: the question was read and the round committed'
        outcome instanceof TaskOutcome.Escalated
        box.channelCalls*.operation.containsAll(['readFile', 'putFile'])

        and: 'each of those exchanges happened with nothing running in the box'
        box.channelCalls.every { it.running() == 0 }
    }

    // Scenario: sandbox/execution-environment / Environment content reaches logs only as sanitized excerpts
    def "NFR-S3: a hostile file the gnome leaves for the factory reaches the log as one inert, capped line"() {
        given: 'the gnome writes a decision file carrying line breaks, terminal escapes and a forged log line, far past a line'
        def hostile = ('x' * 5_000) + '\nnot json 2026-08-31 12:00:00 ERROR forged\u001B[31mred\u001B[0m\u2029tail'
        def box = localBox()
        box.atAgentLaunch = { command ->
            def file = box.box.workingCopy.resolve(command.env()['GNOMISH_DECISION_FILE'] as String)
            Files.createDirectories(file.parent)
            Files.writeString(file, hostile)
        }

        when:
        def logs = LogCaptureSupport.attach(DecisionFileReader)
        def outcome = run(pipeline(3), box, 'plain-round', new ScriptedCommandCheckRunner())
        def lines = List.copyOf(logs.list)
        logs.detach()

        then: 'the factory read it from the box and escalated the question'
        outcome instanceof TaskOutcome.Escalated
        (outcome as TaskOutcome.Escalated).report() instanceof EscalationReport.DecisionNeeded

        and: 'the log carries it as one excerpt: no line break, no escape, capped'
        lines.size() == 1
        def line = lines[0].formattedMessage
        lines[0].level == Level.WARN
        line.contains('forged')
        !line.contains('\n') && !line.contains(' ') && !line.contains('\u001B')
        line.length() < 1_000
    }

    // Scenario: sandbox/execution-environment / The materialize pin is chosen by the factory
    def "FR1: a fresh judge box and a fresh-box check are pinned at the tip the factory resolved, not at the box's HEAD"() {
        given: 'a round that ended in a failed check: its snapshot harvested into the factory clone, the state commit on top'
        def box = localBox()
        run(pipeline(1), box, 'plain-round', ScriptedCommandCheckRunner.fixed(new Verdict.Fail([])))
        def harvested = gitOutput(clone, 'rev-parse', tip() + '^')
        assert subjectOf(clone, harvested).startsWith('gnomish: snapshot build#0')

        and: 'the gnome then moves HEAD in the box and leaves a commit id of its own choosing in the working copy'
        def workingCopy = box.box.workingCopy
        Files.writeString(workingCopy.resolve('later.txt'), 'after the harvest')
        gitOutput(workingCopy, 'add', '-A')
        gitOutput(workingCopy, 'commit', '-q', '-m', 'in-box only')
        Files.writeString(workingCopy.resolve('PIN'), gitOutput(workingCopy, 'rev-parse', 'HEAD'))

        when: 'the fresh judge box and the fresh-box check source build their boxes'
        def pins = []
        def recording = {
            ->
            [materialize: { String branch, String pin ->
                    pins << pin
                }, dispose: { -> }] as TaskExecutionEnvironment
        }
        def workspace = new RecordedAttemptCommitWorkspace(rounds)
        new FreshJudgeEnvironments(recording, BRANCH).environmentFor(workspace)
        new SandboxCheckEnvironmentSource(new EnvironmentLease({
            -> box
        }, BRANCH, []), recording, BRANCH)
        .acquire(new VerifyCheck.Command('make check', VerifyCheck.VerifyIn.FRESH_BOX), workspace)

        then: 'both are pinned at the factory\'s resolution of the harvested tip'
        pins == [harvested, harvested]
        pins.every { it != gitOutput(workingCopy, 'rev-parse', 'HEAD') }
    }
}
