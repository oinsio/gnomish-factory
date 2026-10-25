package com.github.oinsio.gnomish.app

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.app.port.run.ImageContractRefusedException
import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.environment.ContainerBindingProvider
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport

/**
 * FR13, NFR-R3, UX3 of add-sandbox-base-image (design D17, D19): {@code serve} assesses the
 * operator preconditions after the tracker section is required and before the label-provisioning
 * smoke test. A refusal is a startup failure — exit 1, the check's own message on the console, the
 * startup's one coded line — and the tracker is never touched: no provisioning, no listing, no
 * claim. Preconditions that hold reach the smoke test with the image asked once.
 */
class ServeCommandPreconditionsSpec extends ServeCommandSpecBase {

    private static final String IMAGE = 'gnomish/fixture-image'

    /** Counts provisioning attempts and stops startup there, so a pass is observed and nothing runs. */
    private static class CountingFactory implements TrackerAdapterFactory {
        int creates

        String type() {
            'github'
        }

        Tracker create(TrackerAdapterContext context) {
            creates++
            throw new IllegalStateException('stopped at provisioning')
        }

        TaskRef expandRef(TrackerConfig config, String rawRef) {
            throw new UnsupportedOperationException('not used by this fixture')
        }
    }

    def factory = new CountingFactory()
    LogCaptureSupport logs

    def setup() {
        logs = LogCaptureSupport.attach(ServeStartupGates, Level.INFO)
        writeConfig(GITHUB_TRACKER_SECTION)
    }

    def cleanup() {
        logs.detach()
    }

    private ServeCommand command(boolean runtimeAnswers, SandboxImageReadiness readiness) {
        def sandbox = new SandboxProperties(IMAGE, null, null, null, null, null, false, null, null, null, null)
        def selector = new SandboxModeSelector(new BindingProperties(BindingNames.CONTAINER, [:]), sandbox,
        AdapterBindingRegistry.ratified([
            new ContainerBindingProvider()
        ], BindingTrustTable.firstParty()),
        { runtimeAnswers } as ContainerRuntimeProbe)
        def support = new ContainerTakeSupport(selector, { a, b, c, d, e ->
            throw new IllegalStateException('startup builds no container support')
        } as ContainerSupportFactory)
        ServeCommands.of(
                newAssembly(testProperties(instanceName: INSTANCE_NAME)),
                TaskGitFixture.real(),
                registeredClone,
                'taskId',
                testProperties(instanceName: INSTANCE_NAME),
                new ServeProperties(0, null, null, null, null, null, null, null, null, null),
                new TrackerWiring([github: factory], MapSecretsProvider.NONE, TrackerValidatorStub.acceptingGithubSource(),
                VirtualTimeEquipment.create()),
                new CapturingStarter(), SandboxLifecyclePass.NONE, support, LiveConsoleIO.onStderr(), readiness)
    }

    private static String captureStderr(Closure action) {
        def originalErr = System.err
        def captured = new ByteArrayOutputStream()
        System.err = new PrintStream(captured, true, 'UTF-8')
        try {
            action.call()
        } finally {
            System.err = originalErr
        }
        captured.toString('UTF-8')
    }

    /** Runs {@code command} to its startup exit; returns the exit code and what it printed. */
    private List runToExit(ServeCommand command) {
        Integer code = null
        def stderr = captureStderr {
            try {
                command.run(args('serve', "--dir=$projectDir"))
            } catch (ServeExitCodeException exit) {
                code = exit.exitCode()
            }
        }
        [code, stderr]
    }

    private void assertRefusedLine(String fragment) {
        def refused = logs.list.findAll {
            it.formattedMessage.startsWith(OperatorEvent.SERVE_OPERATOR_PRECONDITION_REFUSED.head())
        }
        assert refused.size() == 1
        assert refused[0].level == Level.ERROR
        assert refused[0].formattedMessage.contains(fragment)
        assert refused[0].throwableProxy == null
    }

    // Scenario: serve/factory-serve / A clone on the files backend refuses the daemon at startup
    def "FR13, UX3: a files-backend clone exits 1 with the migration remedy and no tracker call"() {
        given:
        gitOutput(projectDir, 'refs', 'migrate', '--ref-format=files')
        SandboxImageReadiness readiness = Mock()

        when:
        def (Integer code, String stderr) = runToExit(command(true, readiness))

        then:
        code == 1
        stderr.contains('gnomish serve: startup failed: ')
        stderr.contains('refs migrate --ref-format=reftable')
        factory.creates == 0
        0 * readiness._
        assertRefusedLine('CLONE_REF_FORMAT')
    }

    // Scenario: serve/factory-serve / An unreachable container runtime refuses the daemon at startup
    def "FR13: an unreachable runtime exits 1 naming it, before the smoke test runs"() {
        when:
        def (Integer code, String stderr) = runToExit(command(false, ServeCommands.PASSING_IMAGE))

        then:
        code == 1
        stderr.contains('the Docker runtime is unreachable')
        factory.creates == 0
        assertRefusedLine('CONTAINER_RUNTIME')
    }

    // Scenario: serve/factory-serve / A stale image refuses the daemon at startup
    def "FR13: a stale image exits 1 printing the contract refusal and its FROM line"() {
        given:
        def refusal = new ImageContractRefusedException(IMAGE, null, 'io.gnomish.image-contract', 2,
                'rebuild: FROM ghcr.io/oinsio/gnomish-sandbox-base:1.2')

        when:
        def (Integer code, String stderr) = runToExit(command(true, { String image ->
            throw refusal
        } as SandboxImageReadiness))

        then:
        code == 1
        stderr.contains(refusal.message)
        factory.creates == 0
        assertRefusedLine('rebuild: FROM ghcr.io/oinsio/gnomish-sandbox-base:1.2')
    }

    // Scenario: serve/factory-serve / Preconditions that hold let startup continue unchanged
    def "FR13: preconditions that hold reach the smoke test, the image asked once and no refusal line"() {
        given:
        SandboxImageReadiness readiness = Mock()

        when:
        def (Integer code, String stderr) = runToExit(command(true, readiness))

        then:
        1 * readiness.assess(IMAGE)
        code == 1
        stderr.contains('startup failed provisioning tracker')
        factory.creates == 1
        logs.list.every {
            !it.formattedMessage.startsWith(OperatorEvent.SERVE_OPERATOR_PRECONDITION_REFUSED.head())
        }
    }
}
