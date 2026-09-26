package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport

/**
 * FR12, FR17, D7 of add-factory-serve: a {@link ServeCommand} that cannot bind its tracker never
 * reaches the scheduler. A project with no {@code tracker:} section is a usage error; a binding
 * whose {@code create()} fails — the label-provisioning smoke test — exits 1 before anything is
 * claimed, telling the operator on the console and the log file on its own record.
 *
 * <p>Implements FR12, FR17, D7 of add-factory-serve. Implements FR2, FR7 of
 * harden-logging-observability.
 */
class ServeStartupFailureSpec extends ServeCommandSpecBase {

    // No `repo` key at all — TrackerValidatorStub.acceptingGithub() accepts any subsection
    // content, so this parses fine and exercises bindingDescription's repo == null branch.
    private static final String GITHUB_TRACKER_SECTION_NO_REPO = '''
tracker:
  type: github
  github:
    api-url: https://api.github.com
'''

    private static String captureStderr(Closure action) {
        def originalErr = System.err
        def captured = new ByteArrayOutputStream()
        System.err = new PrintStream(captured, true, 'UTF-8')
        try {
            action.call()
        } finally {
            System.err = originalErr
        }
        return captured.toString('UTF-8')
    }

    private static TrackerAdapterFactory factoryThrowingOnCreate(RuntimeException failure) {
        new TrackerAdapterFactory() {
                    String type() {
                        'github'
                    }

                    Tracker create(SecretsProvider secrets, TrackerConfig config, String instanceId) {
                        throw failure
                    }

                    TaskRef expandRef(TrackerConfig config, String rawRef) {
                        throw new UnsupportedOperationException('not used by this fixture')
                    }
                }
    }

    /** Runs {@code command} expecting the startup exit; returns the exception and the stderr it printed. */
    private List runExpectingExit(ServeCommand command) {
        ServeExitCodeException ex = null
        def stderr = captureStderr {
            try {
                command.run(args('serve', "--dir=$projectDir"))
            } catch (ServeExitCodeException caught) {
                ex = caught
            }
        }
        [ex, stderr]
    }

    def "no tracker section in config.yaml refuses with UsageException (FR17)"() {
        given:
        writeConfig()
        def command = newCommand([:], new CapturingStarter())

        when:
        command.run(args('serve', "--dir=$projectDir"))

        then:
        def ex = thrown(UsageException)
        ex.message.contains('tracker')
    }

    def "unreachable tracker binding fails startup with exit code 1 before claiming anything"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        def factory = factoryThrowingOnCreate(new IllegalStateException('connection refused'))
        def command = newCommand([github: factory], new CapturingStarter())

        when:
        def (ServeExitCodeException ex, String stderr) = runExpectingExit(command)

        then:
        ex != null
        ex.exitCode() == 1
        0 * tracker.listReady(_)
        0 * tracker.claim(_, _)

        and: 'bindingDescription (FR12, D7) names both the type and the configured repo'
        stderr.contains("gnomish serve: startup failed provisioning tracker 'github' (acme/widgets): connection refused")
    }

    // FR12, D7: bindingDescription's other branch — when no `repo` key is configured, the
    // startup-failure message names only the tracker type, with no parenthesized repo suffix,
    // proving the `repo == null` conditional (and not just its negation) is actually exercised.
    def "startup failure message names only the tracker type when no repo is configured"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION_NO_REPO)
        def factory = factoryThrowingOnCreate(new IllegalStateException('connection refused'))
        def command = newCommand([github: factory], new CapturingStarter())

        when:
        def (ServeExitCodeException ex, String stderr) = runExpectingExit(command)

        then:
        ex != null
        ex.exitCode() == 1

        and: 'no parenthesized repo suffix — a distinct, non-empty message from the repo-configured branch'
        stderr.contains("gnomish serve: startup failed provisioning tracker 'github': connection refused")
        !stderr.contains('(')
    }

    // FR2, FR7 of harden-logging-observability: a startup that dies provisioning the tracker used
    // to leave the log file empty — the one record of why the daemon never came up went to a
    // terminal nobody keeps. The console sentence stays; the file now also gets the failure with
    // its stack.
    def "a startup provisioning failure is logged with its throwable, not only printed"() {
        given:
        writeConfig(GITHUB_TRACKER_SECTION)
        def failure = new IllegalStateException('connection refused')
        def command = newCommand([github: factoryThrowingOnCreate(failure)], new CapturingStarter())
        def capture = LogCaptureSupport.attach(ServeCommand)

        when:
        runExpectingExit(command)

        then:
        capture.list.size() == 1
        capture.list[0].level == Level.ERROR
        capture.list[0].formattedMessage.startsWith(OperatorEvent.SERVE_TRACKER_PROVISION_FAILED.head())
        capture.list[0].formattedMessage.contains("provisioning tracker 'github' (acme/widgets)")

        and: 'the throwable rides along, so the stack and cause chain survive'
        capture.list[0].throwableProxy.message == 'connection refused'

        cleanup:
        capture.detach()
    }
}
