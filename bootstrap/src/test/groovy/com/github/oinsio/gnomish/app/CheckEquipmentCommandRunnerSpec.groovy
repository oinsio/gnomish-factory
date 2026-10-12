package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import spock.lang.Specification

/**
 * {@link CheckEquipment#commandRunner}: the command-check runner a run gets from the check
 * equipment (task 3.5 and the {@code CheckEquipment} row of collapse-composition-roots) composes
 * its child environments from the run's own allowlist in both modes, and runs its checks in the
 * sandbox pieces' check environments in container mode only.
 *
 * <p>Implements FR4, NFR-S1 of collapse-composition-roots; FR9, FR13 of add-sandbox-core; NFR-S3 of
 * tighten-spec-contract.
 */
class CheckEquipmentCommandRunnerSpec extends Specification {

    private final CheckEquipment equipment = new CheckEquipment(
    new FilesExistCheckRunner(),
    new ShellCommandCheckRunner(new VirtualClock()),
    [:],
    MapSecretsProvider.NONE,
    new FactoryProperties('check-equipment', null, null, null),
    VirtualTimeEquipment.create())

    private final ChildEnvAllowlist childEnv = TestChildEnvironment.hostAllowlist(['PATH'], ['TRACKER_TOKEN'])

    // FR9 of add-sandbox-core, NFR-S1: on the host the run's allowlist reaches the runner, so a
    //     tracker credential it names is scrubbed from every command check's environment.
    def "host mode: the runner composes child environments from the run's allowlist"() {
        when:
        def runner = equipment.commandRunner(childEnv, null)

        then:
        runner.childEnv().is(childEnv)
    }

    // FR13 of add-sandbox-core: in container mode the checks run in the sandbox pieces' check
    //     environments, and the run's allowlist is still the one the runner carries.
    def "container mode: the runner runs checks in the sandbox pieces' check environments"() {
        given:
        def checkEnvironments = Mock(CheckEnvironmentSource)
        def pieces = new SandboxRunPieces(null, null, checkEnvironments, null, null, new CurrentRound(), null)

        when:
        def runner = equipment.commandRunner(childEnv, pieces)

        then:
        runner.environments().is(checkEnvironments)
        runner.childEnv().is(childEnv)
    }

    // NFR-S3 of tighten-spec-contract (F107): the run's allowlist the equipment builds resolves every
    //     value — base set and passthrough — through the equipment's secrets port, and still refuses
    //     and scrubs the credential names the run declares
    def "NFR-S3: the run's child-environment allowlist resolves through the equipment's secrets port"() {
        given:
        def secrets = new MapSecretsProvider([PATH: '/from-the-port', TOOL: 'tool-from-port', TRACKER_TOKEN: 'never'])
        def ported = new CheckEquipment(new FilesExistCheckRunner(), new ShellCommandCheckRunner(new VirtualClock()), [:],
        secrets, new FactoryProperties('check-equipment', null, null, null), VirtualTimeEquipment.create())

        when:
        def allowlist = ported.childEnv(['TOOL'], new PipelineDefinition('1', new AutonomyLimits(3), []), ['TRACKER_TOKEN'])

        then:
        allowlist.compose([
            'PATH',
            'TRACKER_TOKEN',
            'UNSET_IN_PORT'
        ], [:]) == [PATH: '/from-the-port', TOOL: 'tool-from-port']

        when: 'the passthrough names a declared credential'
        ported.childEnv(['TRACKER_TOKEN'], new PipelineDefinition('1', new AutonomyLimits(3), []), ['TRACKER_TOKEN'])

        then:
        thrown(IllegalArgumentException)
    }
}
