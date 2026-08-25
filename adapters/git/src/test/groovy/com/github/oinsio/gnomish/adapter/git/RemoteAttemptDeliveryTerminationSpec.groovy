package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef
import com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery
import java.nio.file.Path
import java.time.Duration
import org.slf4j.LoggerFactory
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

        then: 'NFR-O2: the one WARN says the remote never answered, never that the push failed'
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('delivery push timed out')
        !warnings[0].formattedMessage.contains('interrupted')

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
        def warnings = events.findAll { it.level == Level.WARN }
        warnings.size() == 1
        warnings[0].formattedMessage.contains('delivery push was interrupted')
        !warnings[0].formattedMessage.contains('timed out')

        and:
        outcome instanceof AttemptDelivery.Outcome.Undeliverable
        def undeliverable = outcome as AttemptDelivery.Outcome.Undeliverable
        undeliverable.reason().contains('could not be verified')
        undeliverable.details().contains('was interrupted before it finished')

        and:
        pushAttempts(tempDir).toFile().readLines().size() == 1
    }

    private static List<ILoggingEvent> capture(Closure<?> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(RemoteAttemptDelivery)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        appender.list
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
