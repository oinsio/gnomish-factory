package com.github.oinsio.gnomish.app.doctor

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.pipeline.TrackerValidatorStub
import com.github.oinsio.gnomish.adapter.sandbox.DiscoveredBindings
import com.github.oinsio.gnomish.app.CheckEquipment
import com.github.oinsio.gnomish.app.DoctorProjectReadings
import com.github.oinsio.gnomish.app.DoctorProjectReadings.StageBindings
import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.TrackerAdapterFactory
import com.github.oinsio.gnomish.app.TrackerWiring
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.app.port.secrets.fake.MapSecretsProvider
import com.github.oinsio.gnomish.app.project.ProjectResolution
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Path

/**
 * The run data a doctor check's spec hands it (design D3 of add-doctor-command): a registered clone
 * with a loaded pipeline bound to the adapters the feature chose, a run outside any project, a law
 * that did not load — and the real {@link DoctorProjectReadings} over owners whose inputs the spec
 * plants: a tracker type declaring {@link #TRACKER_TOKEN}, a secrets port over a map.
 */
final class DoctorSpecSupport {

    static final RegisteredClone CLONE = RegisteredCloneFixture.unregistered(Path.of('/srv/gnomish'), Path.of('/src/widgets'))

    /** The credential the planted tracker type declares. */
    static final String TRACKER_TOKEN = 'GNOMISH_DOCTOR_TEST_TOKEN'

    /** The tracker type whose adapter declares {@link #TRACKER_TOKEN}. */
    static final String TRACKER_TYPE = 'doctor-test'

    /** The fix every unloaded reading of these fixtures carries. */
    static final String LAW_FIX = DoctorSurvey.LAW_FIX

    private DoctorSpecSupport() {}

    /** A one-stage definition, with a {@code tracker} section of {@link #TRACKER_TYPE} when asked. */
    static PipelineDefinition definition(boolean tracker = false, List<String> stages = ['build']) {
        new PipelineDefinition('1', new AutonomyLimits(3), stages.collect {
            new StageDefinition(
                    it, 'purpose', [], [],
                    new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:], Sandbox.none()),
                    'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        }, tracker ? new TrackerConfig(TRACKER_TYPE, 3) : null)
    }

    /** A registered clone whose law loaded, its stages bound as asked. */
    static DoctorContext bound(boolean container, boolean host, PipelineDefinition definition = definition()) {
        DoctorContext.of(new ProjectResolution.Registered(CLONE), {
            new PipelineReading.Loaded(definition, new StageBindings(container, host))
        })
    }

    /** A registered clone whose law did not load. */
    static DoctorContext unloaded(List<String> reasons = ['config.yaml:3: bad']) {
        DoctorContext.of(new ProjectResolution.Registered(CLONE), {
            new PipelineReading.Unloaded(reasons, LAW_FIX)
        })
    }

    /** The working directory outside any registered clone (FR2). */
    static DoctorContext withoutProject() {
        DoctorContext.of(new ProjectResolution.Unregistered(Path.of('/tmp/elsewhere'), false, 'not registered'), {
            throw new AssertionError('no pipeline is read outside a project')
        })
    }

    /**
     * The real readings, over a secrets port holding {@code secrets} (or the spec's own {@code
     * secretsPort}), the operator's {@code passthrough} names and bindings, a pipeline source
     * answering {@code source}'s load, and the tracker adapter {@code registry} when the spec plants
     * its own.
     */
    static DoctorProjectReadings readings(Map<String, String> secrets = [:], Map options = [:]) {
        def time = VirtualTimeEquipment.create()
        def secretsPort = (options.secretsPort ?: new MapSecretsProvider(secrets)) as SecretsProvider
        def factory = [credentialEnvVars: { TrackerConfig config ->
                [TRACKER_TOKEN]
            }] as TrackerAdapterFactory
        def registry = (options.registry ?: [(TRACKER_TYPE): factory]) as Map<String, TrackerAdapterFactory>
        def wiring = new TrackerWiring(registry, secretsPort,
                (options.source ?: TrackerValidatorStub.plainSource()) as PipelineSource, time)
        def checks = new CheckEquipment(new FilesExistCheckRunner(), new ShellCommandCheckRunner(time.clock()), [:],
        secretsPort, new FactoryProperties(null, null, null, null), time)
        def sandbox = new SandboxProperties(null, null, null, null, [], (options.passthrough ?: []) as List<String>,
        false, null, null, null, null)
        new DoctorProjectReadings(wiring, checks, (options.bindings ?: new BindingProperties('host', [:])) as BindingProperties,
        DiscoveredBindings.real(), sandbox)
    }
}
