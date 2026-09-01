package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7 of bound-subprocess-commands: the attempt-commit delivery must not spend its one bounded
 * re-attempt on a push that never reached a remote outcome, and must report the result as unknown
 * rather than negative. Both outcomes are Undeliverable, which the engine resolves as CannotVerify
 * — an infrastructure failure that burns no stage attempt — never a quality failure.
 *
 * <p>Driven through the runner's git-binary seam ({@link StallingGitFixture}); the real-remote
 * behaviour of the same class lives in {@link RemoteAttemptDeliverySpec}.
 */
class RemoteAttemptDeliveryTerminationSpec extends Specification implements StallingGitFixture {

    private static final String BRANCH = 'gnomish/task-1'

    @TempDir
    Path tempDir

    def "FR7: a timed-out push is reported as unknown, not as a delivery failure, and is not re-attempted"() {
        given:
        AttemptDelivery.Outcome outcome = null

        when:
        def events = capture {
            outcome = delivery(Duration.ofSeconds(2)).ensureDelivered(workspace())
        }

        // FR12 of harden-logging-observability: the Undeliverable outcome below carries this same
        //     fact into the check's CannotVerify verdict, which the engine reports — so the line
        //     here is diagnosis, not a second operator-facing report of one fault.
        then: 'NFR-O2: the one line says the remote never answered, never that the push failed'
        events.findAll { it.level == Level.WARN }.isEmpty()
        def notes = events.findAll { it.level == Level.DEBUG }
        notes.size() == 1
        notes[0].formattedMessage.contains('delivery push timed out')
        !notes[0].formattedMessage.contains('interrupted')

        and: 'CannotVerify territory — the reason says nobody knows, not that the push was refused'
        outcome instanceof AttemptDelivery.Outcome.Undeliverable
        def undeliverable = outcome as AttemptDelivery.Outcome.Undeliverable
        undeliverable.reason().contains('could not be verified')
        undeliverable.details().contains('was cut off on its deadline')
        undeliverable.details().contains('is unknown')
        !undeliverable.details().contains('failed twice')

        and: 'one push: a second full deadline on a proven-unresponsive remote establishes nothing'
        pushAttempts(tempDir).toFile().readLines().size() == 1
    }

    def "FR7: an interrupted push is reported as unknown and is not re-attempted"() {
        given:
        AttemptDelivery.Outcome outcome = null
        def delivery = delivery(Duration.ofSeconds(30))

        when:
        def events = capture {
            def runner = new Thread({
                outcome = delivery.ensureDelivered(workspace())
            })
            runner.start()
            awaitPushStarted(tempDir)
            runner.interrupt()
            runner.join(Duration.ofSeconds(30).toMillis())
        }

        then: 'NFR-O2: the same line, in the words of the other outcome'
        events.findAll { it.level == Level.WARN }.isEmpty()
        def notes = events.findAll { it.level == Level.DEBUG }
        notes.size() == 1
        notes[0].formattedMessage.contains('delivery push was interrupted')
        !notes[0].formattedMessage.contains('timed out')

        and:
        outcome instanceof AttemptDelivery.Outcome.Undeliverable
        def undeliverable = outcome as AttemptDelivery.Outcome.Undeliverable
        undeliverable.reason().contains('could not be verified')
        undeliverable.details().contains('was interrupted before it finished')

        and:
        pushAttempts(tempDir).toFile().readLines().size() == 1
    }

    /** Migrated to the shared helper (`.claude/rules/logging.md`) when task 5.4 touched this spec. */
    private static List<ILoggingEvent> capture(Closure<?> emit) {
        def logs = LogCaptureSupport.attach(RemoteAttemptDelivery, Level.DEBUG)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    private RemoteAttemptDelivery delivery(Duration deadline) {
        new RemoteAttemptDelivery(new GitProcessRunner(stallingGit(tempDir).toString(), deadline), tempDir, BRANCH)
    }

    /** A workspace carrying a commit the stand-in's empty ls-remote answer never confirms. */
    private static RecordedAttemptCommitWorkspace workspace() {
        def ref = new AttemptCommitRef()
        ref.record('2222222222222222222222222222222222222222')
        new RecordedAttemptCommitWorkspace(ref)
    }
}
