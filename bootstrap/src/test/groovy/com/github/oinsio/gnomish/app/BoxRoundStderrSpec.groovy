package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path

/**
 * The round's stderr for a box round, through the engine and the real round adapters: the box's
 * exec client writes the CLI's stderr into the round scratch directory on the host, the tail
 * reaches the diagnostics — a DEBUG line on success, the escalation on failure — and the directory
 * leaves with the round however it ended (design D18 of fix-terminal-receipt-convergence).
 *
 * <p>FR20 of fix-terminal-receipt-convergence.
 */
class BoxRoundStderrSpec extends RoundFlowSpecBase {

    static final String ERROR_LINE = 'claude: error: the error at the end of a chatty stderr'

    /** The package-private owner of the round scratch directory, captured by its logger's name. */
    static final String SCRATCH_LOGGER = 'com.github.oinsio.gnomish.adapter.agent.RoundScratchDirectory'

    Path stderrFile

    LogCaptureSupport logs

    def cleanup() {
        logs?.detach()
    }

    private ObservedBox observedBox() {
        def box = localBox()
        box.atAgentLaunch = { command ->
            stderrFile = (command.stderr() as StderrPolicy.ToFile).file()
        }
        box
    }

    // FR20: a box round's chatty stderr lands in a host file, and the round still completes
    // Scenario: executor/agent-executor / A chatty stderr cannot block the round
    // Scenario: executor/agent-executor / The stderr file leaves with the round
    def "FR20: a box round whose CLI wrote 200 KiB to stderr completes, logs the tail at DEBUG, and leaves no file on the host"() {
        given:
        logs = LogCaptureSupport.attach(SCRATCH_LOGGER, Level.DEBUG)

        when:
        def outcome = run(pipeline(3), observedBox(), 'chatty-stderr', ScriptedCommandCheckRunner.fixed(new Verdict.Pass(null)))

        then:
        outcome instanceof TaskOutcome.Completed
        logs.list.count {
            it.level == Level.DEBUG && it.formattedMessage.contains(ERROR_LINE)
        } == 1

        and: 'the stderr was a host file in the round scratch directory, and the directory left with the round'
        stderrFile != null
        stderrFile.parent.fileName.toString().startsWith('gnomish-round-')
        !Files.exists(stderrFile.parent)
    }

    // FR20: the tail of a box round that failed reaches the escalation the reviewer reads
    // Scenario: executor/agent-executor / A failed round carries its stderr tail
    // Scenario: executor/agent-executor / The stderr file leaves with the round
    def "FR20: a box round whose CLI exited without a result event escalates with the stderr's tail, and leaves no file"() {
        given:
        logs = LogCaptureSupport.attach(SCRATCH_LOGGER, Level.DEBUG)

        when:
        def outcome = run(pipeline(3), observedBox(), 'chatty-stderr-no-result', new ScriptedCommandCheckRunner())

        then:
        outcome instanceof TaskOutcome.Escalated
        def report = (outcome as TaskOutcome.Escalated).report() as EscalationReport.CannotExecute
        report.cause().forConsole().contains(ERROR_LINE)

        and: 'the tail is the failure\'s, not a DEBUG line beside it'
        logs.list.isEmpty()

        and:
        !Files.exists(stderrFile.parent)
    }
}
