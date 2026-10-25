package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeProbe
import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.take.DaemonConditionKind
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.sandbox.AdapterBindingRegistry
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.BindingProperties
import com.github.oinsio.gnomish.sandbox.BindingTrustTable
import com.github.oinsio.gnomish.sandbox.HostBindingProvider
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR13, NFR-R3, UX3 of add-sandbox-base-image (design D17): the {@code serve} startup verdict runs
 * the checks a slot meets, in the slot's order — ref format, plan, image — against the daemon's
 * clone, definition and configured image, and the first refusal is the verdict. The image is asked
 * only for a boxed pipeline; a configuration refusal of the plan and an unclassified failure leave
 * unchanged, as from a slot.
 */
class ServePreconditionsSpec extends Specification implements SandboxBindingFixtures {

    private static final RegisteredClone CLONE =
    RegisteredCloneFixture.unregistered(Path.of('/srv/gnomish'), Path.of('/src/widgets'))
    private static final String IMAGE = 'gnomish/sandbox:1'

    TaskBranchGit branches = Mock()
    SandboxImageReadiness readiness = Mock()

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition(
                    'build', 'purpose', [], [],
                    new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:], Sandbox.none()),
                    'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
    }

    private static SandboxProperties sandbox(String image) {
        new SandboxProperties(image, null, null, null, [], [], false, null, null, null, null)
    }

    private ServePreconditions preconditions(String binding, boolean runtimeAnswers, String image = IMAGE) {
        def registry = AdapterBindingRegistry.ratified(
                [
                    new HostBindingProvider(),
                    containerProvider()
                ], BindingTrustTable.firstParty())
        def selector = new SandboxModeSelector(new BindingProperties(binding, [:]), sandbox(image), registry, {
            -> runtimeAnswers
        } as ContainerRuntimeProbe)
        new ServePreconditions(branches, selector, readiness, sandbox(image))
    }

    // Scenario: serve/factory-serve / Preconditions that hold let startup continue unchanged
    def "FR13: every precondition holding is a pass, with each check asked once, in the slot's order"() {
        when:
        def verdict = preconditions(BindingNames.CONTAINER, true).assess(CLONE, pipeline())

        then:
        1 * branches.requireRefFormat(CLONE.clonePath())

        then:
        1 * readiness.assess(IMAGE)
        0 * _
        verdict instanceof ServePreconditions.StartupAssessment.Pass
    }

    def "FR13: a host-bound daemon is never asked for an image"() {
        when:
        def verdict = preconditions(BindingNames.HOST, false, null).assess(CLONE, pipeline())

        then:
        1 * branches.requireRefFormat(CLONE.clonePath())
        0 * readiness._
        verdict instanceof ServePreconditions.StartupAssessment.Pass
    }

    // Scenario: serve/factory-serve / A clone on the files backend refuses the daemon at startup
    def "FR13, UX3: the ref-format refusal is the verdict, and nothing after it is asked"() {
        given:
        def refusal = new CloneRefFormatRefusedException('/src/widgets', 'reftable', UntrustedText.subprocess('files'))
        branches.requireRefFormat(_) >> { throw refusal }

        when:
        def verdict = preconditions(BindingNames.CONTAINER, true).assess(CLONE, pipeline())

        then:
        0 * readiness._
        verdict == new ServePreconditions.StartupAssessment.Refused(refusal, DaemonConditionKind.CLONE_REF_FORMAT)
    }

    // Scenario: serve/factory-serve / An unreachable container runtime refuses the daemon at startup
    def "FR13: the plan's runtime refusal is the verdict, before the image is asked"() {
        when:
        def verdict = preconditions(BindingNames.CONTAINER, false).assess(CLONE, pipeline())

        then:
        0 * readiness._
        verdict instanceof ServePreconditions.StartupAssessment.Refused
        (verdict as ServePreconditions.StartupAssessment.Refused).kind() == DaemonConditionKind.CONTAINER_RUNTIME
        (verdict as ServePreconditions.StartupAssessment.Refused).refusal().message.contains('Docker runtime is unreachable')
    }

    // Scenario: serve/factory-serve / A stale image refuses the daemon at startup
    def "FR13: an image refusal is the verdict, naming its kind: #kind"() {
        given:
        def refusal = OperatorPreconditionRefusalSamples.of(kind)
        readiness.assess(IMAGE) >> { throw refusal }

        when:
        def verdict = preconditions(BindingNames.CONTAINER, true).assess(CLONE, pipeline())

        then:
        verdict == new ServePreconditions.StartupAssessment.Refused(refusal, kind)

        where:
        kind << [
            DaemonConditionKind.IMAGE_CONTRACT,
            DaemonConditionKind.BOX_GIT_FLOOR
        ]
    }

    def "FR13: the plan's configuration refusal leaves as the usage error it is"() {
        when:
        preconditions(BindingNames.CONTAINER, true, null).assess(CLONE, pipeline())

        then:
        thrown(UsageException)
        0 * readiness._
    }

    def "NFR-R2: an unclassified failure is not a refusal and leaves unchanged"() {
        given:
        def failure = new IllegalStateException('image inspect failed')
        readiness.assess(IMAGE) >> { throw failure }

        when:
        preconditions(BindingNames.CONTAINER, true).assess(CLONE, pipeline())

        then:
        def thrownFailure = thrown(IllegalStateException)
        thrownFailure.is(failure)
    }

    def "FR15: a recheck runs the startup check of its kind against the same clone, definition and image: #kind"() {
        given:
        def rechecks = preconditions(BindingNames.CONTAINER, true).rechecks(CLONE, pipeline())

        when:
        def answer = rechecks.recheck(kind)

        then:
        refFormatCalls * branches.requireRefFormat(CLONE.clonePath())
        imageCalls * readiness.assess(IMAGE)
        0 * _
        answer.isEmpty()

        where:
        kind | refFormatCalls | imageCalls
        DaemonConditionKind.CLONE_REF_FORMAT | 1 | 0
        DaemonConditionKind.CONTAINER_RUNTIME | 0 | 0
        DaemonConditionKind.IMAGE_CONTRACT | 0 | 1
        DaemonConditionKind.BOX_GIT_FLOOR | 0 | 1
    }

    def "FR15: a recheck that meets a refusal answers it typed, with the check's message and the kind it names: #kind"() {
        given:
        def refusal = OperatorPreconditionRefusalSamples.of(kind)
        readiness.assess(IMAGE) >> { throw refusal }

        when:
        def answer = preconditions(BindingNames.CONTAINER, true).rechecks(CLONE, pipeline())
                .recheck(DaemonConditionKind.IMAGE_CONTRACT)

        then:
        answer.get().kind() == kind
        answer.get().reason().forLog() == refusal.message

        where:
        kind << [
            DaemonConditionKind.IMAGE_CONTRACT,
            DaemonConditionKind.BOX_GIT_FLOOR,
            DaemonConditionKind.CONTAINER_RUNTIME
        ]
    }

    def "FR15: the runtime's recheck is the plan, which meets an unreachable runtime first"() {
        when:
        def answer = preconditions(BindingNames.CONTAINER, false).rechecks(CLONE, pipeline())
                .recheck(DaemonConditionKind.CONTAINER_RUNTIME)

        then:
        answer.get().kind() == DaemonConditionKind.CONTAINER_RUNTIME
        answer.get().reason().forLog().contains('Docker runtime is unreachable')
    }

    def "NFR-R2: a recheck's unclassified failure leaves unchanged, never as a refusal"() {
        given:
        def failure = new IllegalStateException('image inspect failed')
        readiness.assess(IMAGE) >> { throw failure }

        when:
        preconditions(BindingNames.CONTAINER, true).rechecks(CLONE, pipeline()).recheck(DaemonConditionKind.BOX_GIT_FLOOR)

        then:
        def thrownFailure = thrown(IllegalStateException)
        thrownFailure.is(failure)
    }

    def "FR15: the remote has no startup check to recheck"() {
        when:
        preconditions(BindingNames.CONTAINER, true).rechecks(CLONE, pipeline()).recheck(DaemonConditionKind.REMOTE)

        then:
        thrown(IllegalArgumentException)
        0 * _
    }
}
