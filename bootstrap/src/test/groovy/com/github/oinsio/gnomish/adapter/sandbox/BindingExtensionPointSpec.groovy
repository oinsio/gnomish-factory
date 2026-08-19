package com.github.oinsio.gnomish.adapter.sandbox

import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingResolver
import com.github.oinsio.gnomish.sandbox.SandboxReconciler
import com.github.oinsio.gnomish.sandbox.SegmentPlanner
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The extension-point acceptance test (M4 of open-adapter-binding-registry): a stub first-party
 * backend, staged through the discovery class-loader seam over its own {@code META-INF/services}
 * entry and ratified through an injected trust table, is carried end to end — bound from operator
 * config, reconciled against its declared passport, and planned into segments — with no edit to the
 * discovery mechanism, the registry, or any consumer of a binding.
 *
 * This is what "a backend contributes its binding without core logic edits" means concretely (G1,
 * M1): the only core-side artefact the stand-in needed is its one-line trust registration, supplied
 * here as data.
 *
 * The chain deliberately ends at plan. Generalizing the live run path — mode dispatch and the
 * environment factory — is D5's explicit deferral to the first non-docker backend change, so
 * claiming a run here would over-promise what this change delivers.
 *
 * Implements FR1, FR2, FR6 of open-adapter-binding-registry; M4 of open-adapter-binding-registry.
 */
class BindingExtensionPointSpec extends Specification {

    @TempDir
    Path backendJarRoot

    // M4: bind → reconcile → plan, over a backend nothing in production names
    def "a staged first-party backend is bound, reconciled and planned end to end"() {
        given: 'the stand-in backend staged as its own jar would carry it, trusted by an injected table'
        def loader = StagedBackend.loader(backendJarRoot)
        def registry = SandboxBindingDiscovery.discover(loader, StagedBackend.trustTable())

        and: 'an operator binding every stage to it, and a stage declaring needs its passport satisfies'
        def resolver = new BindingResolver(
                new BindingProperties(StubVmBindingProvider.CONFIG_NAME, [:]), registry)
        def pipeline = pipeline([
            stage('plan', ['egress-control']),
            stage('build', ['docker-inside'])
        ])

        when: 'the pipeline is planned into segments'
        def segments = new SegmentPlanner(resolver).plan(pipeline)

        then: 'both stages share one segment under the stand-in binding'
        segments.size() == 1
        segments[0].binding().configName() == StubVmBindingProvider.CONFIG_NAME
        segments[0].stages()*.name() == ['plan', 'build']

        and: 'reconciliation passes against the passport the trust table ratified for it'
        def reconciler = new SandboxReconciler()
        segments[0].stages().every {
            reconciler.unmetNeeds(it.executor().sandbox(), segments[0].binding().passport()).isEmpty()
        }

        cleanup:
        loader.close()
    }

    // FR6: reconciliation is still fail-closed against the discovered binding's passport — a
    // discovered backend buys no exemption from the contract
    def "a need the staged backend's passport does not satisfy is still reported unmet"() {
        given: 'the stand-in backend bound to a stage declaring a need outside its passport'
        def loader = StagedBackend.loader(backendJarRoot)
        def registry = SandboxBindingDiscovery.discover(loader, StagedBackend.trustTable())
        def binding = registry.require(StubVmBindingProvider.CONFIG_NAME)

        when:
        def unmet = new SandboxReconciler()
                .unmetNeeds(new Sandbox(['no-such-need'], false), binding.passport())

        then: 'the unrecognized need is reported by its raw token, exactly as before the registry'
        unmet == ['no-such-need']

        cleanup:
        loader.close()
    }

    private static PipelineDefinition pipeline(List<StageDefinition> stages) {
        new PipelineDefinition('1', new AutonomyLimits(3), stages)
    }

    private static StageDefinition stage(String name, List<String> needs) {
        new StageDefinition(
                name, 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:], new Sandbox(needs, false)),
                'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }
}
