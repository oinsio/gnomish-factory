package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import org.slf4j.Logger
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Command-check output on its way to the operator (FR15 of add-sandbox-core; FR6, FR8 of
 * type-untrusted-text): the real command runner captures a hostile tail into the verdict, the
 * {@code take} escalation exit parks the task with it, and every log line written on the way and
 * the tracker comment the park publishes are what the operator reads.
 *
 * <p>Implements FR15 of add-sandbox-core; FR6, FR8 of type-untrusted-text.
 */
class CommandOutputNeutralizationSpec extends Specification {

    private static final String ESC = '\u001B'
    private static final String ZWSP = '\u200B'
    private static final TaskRef REF = new TaskRef('PROJ-7')
    private static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')

    @TempDir
    Path tempDir

    Tracker tracker = Mock()

    // Scenario: verification/verification-hardening / Terminal escape attack is neutralized
    def "FR15: command output carrying an ANSI escape and a mention reaches no log line, and the comment breaks both"() {
        when:
        def parked = parkTheAttack()

        then: 'the capture holds the attack, so what follows is about its rendering'
        parked.details.contains(ESC + '[31m')

        and: 'no log line written on the way carries the escape or the mention'
        parked.logLines.every { !it.contains(ESC) && !it.contains('@team') }

        and: 'the comment shows the output with the mention broken and the escape gone'
        parked.comment.contains('owned, ask @' + ZWSP + 'team')
        !parked.comment.contains('@team')
        !parked.comment.contains(ESC)
    }

    // F108: scenario text aligned to what this test asserts
    // Scenario: verification/verification-hardening / Terminal escape attack is neutralized
    def "FR15: the comment renders the CannotVerify report field by field, the output under its Details field, unfenced"() {
        when:
        def parked = parkTheAttack()

        then: 'the report reads field by field (design D6 of type-untrusted-text) ...'
        parked.comment.contains('Could not verify a check named:\n')
        parked.comment =~ /Details:\n[^\n]*owned, ask @${ZWSP}team/

        and: '... with no labeled fence around the command output'
        !parked.comment.contains('Untrusted machine output:')
        !parked.comment.contains('~~~')
    }

    /**
     * Runs a command check that prints the attack and exits as a missing command would, then parks
     * the escalation it causes, capturing every log line the two steps write.
     */
    private Map parkTheAttack() {
        tracker.fetchTask(REF) >> task()
        String published = null
        tracker.park(REF, ParkReason.INFRA, _ as String) >> { args ->
            published = args[2]
        }
        def logs = LogCaptureSupport.attach(Logger.ROOT_LOGGER_NAME, Level.DEBUG)
        try {
            def verdict = new ShellCommandCheckRunner(new VirtualClock()).run(
                    new VerifyCheck.Command("printf '${ESC}[31mowned, ask @team\\n'; exit 127"),
                    new DirectoryWorkspace(tempDir)) as Verdict.CannotVerify
            def report = new EscalationReport.CannotVerify(
                    new CheckRef(0, UntrustedText.manifest('command:lint')), verdict.reason(), verdict.details())
            TakeEscalationExit.exit(new TaskOutcome.Escalated(TaskState.atStageStart('build'), report), order(),
                    VirtualTimeRetries.terminalWrite())
            [details: verdict.details(), comment: published, logLines: logs.list*.formattedMessage]
        } finally {
            logs.detach()
        }
    }

    private static TrackerTask task() {
        new TrackerTask(REF, new TaskSnapshot(REF.id(), UntrustedText.tracker('title'), UntrustedText.tracker('body')),
                new TrackerTaskState.Working(INSTANCE.value()), AbortFacts.none(), false)
    }

    private TakeOrder order() {
        def run = new RunOrder(tempDir, null, new PipelineDefinition('1', new AutonomyLimits(3), []), false)
        new TakeOrder(run, task(), tracker, INSTANCE)
    }
}
