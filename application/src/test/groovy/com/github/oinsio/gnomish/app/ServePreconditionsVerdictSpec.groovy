package com.github.oinsio.gnomish.app

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
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR1, FR4, NFR-R1 of add-doctor-command (design D1): {@code doctor}'s per-kind verdict runs the
 * startup check of its kind through the same ports, never refuses — a refusal is the typed answer a
 * recheck gives, an unclassified failure a value — and judges the image by the readiness port's
 * contract inspection alone, never its {@code assess}, whose floor probe is a container start.
 */
class ServePreconditionsVerdictSpec extends Specification implements SandboxBindingFixtures {

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

    def "FR1, NFR-R1: each kind holds through the one check it owns, the image through the inspection alone: #kind"() {
        when:
        def verdict = preconditions(BindingNames.CONTAINER, true).verdict(kind, CLONE, pipeline())

        then:
        refFormatCalls * branches.requireRefFormat(CLONE.clonePath())
        inspectionCalls * readiness.inspectContract(IMAGE)
        0 * readiness.assess(_)
        0 * _
        verdict == new PreconditionVerdict.Holds()

        where:
        kind | refFormatCalls | inspectionCalls
        DaemonConditionKind.CLONE_REF_FORMAT | 1 | 0
        DaemonConditionKind.CONTAINER_RUNTIME | 0 | 0
        DaemonConditionKind.IMAGE_CONTRACT | 0 | 1
    }

    def "FR4: a refusal is the typed answer a recheck gives, never thrown: #kind"() {
        given:
        def refusal = OperatorPreconditionRefusalSamples.of(kind)
        branches.requireRefFormat(_) >> { throw refusal }
        readiness.inspectContract(IMAGE) >> { throw refusal }

        expect:
        preconditions(BindingNames.CONTAINER, true).verdict(asked, CLONE, pipeline()) ==
                new PreconditionVerdict.Unmet(OperatorPreconditionRefusals.unmet(refusal).get())

        where:
        asked | kind
        DaemonConditionKind.CLONE_REF_FORMAT | DaemonConditionKind.CLONE_REF_FORMAT
        DaemonConditionKind.IMAGE_CONTRACT | DaemonConditionKind.IMAGE_CONTRACT
        DaemonConditionKind.IMAGE_CONTRACT | DaemonConditionKind.CONTAINER_RUNTIME
    }

    def "FR4: an unreachable runtime is the plan's typed refusal"() {
        when:
        def verdict = preconditions(BindingNames.CONTAINER, false)
                .verdict(DaemonConditionKind.CONTAINER_RUNTIME, CLONE, pipeline())

        then:
        verdict instanceof PreconditionVerdict.Unmet
        with((verdict as PreconditionVerdict.Unmet).unmet()) {
            kind() == DaemonConditionKind.CONTAINER_RUNTIME
            reason().forLog().contains('Docker runtime is unreachable')
        }
    }

    def "FR4: a host-bound plan holds the runtime without asking it"() {
        expect:
        preconditions(BindingNames.HOST, false, null).verdict(DaemonConditionKind.CONTAINER_RUNTIME, CLONE, pipeline()) ==
                new PreconditionVerdict.Holds()
    }

    def "FR4: an unclassified failure is a value, the failure as the check threw it"() {
        given:
        def failure = new IllegalStateException('no such image')
        readiness.inspectContract(IMAGE) >> { throw failure }

        when:
        def verdict = preconditions(BindingNames.CONTAINER, true).verdict(DaemonConditionKind.IMAGE_CONTRACT, CLONE, pipeline())

        then:
        verdict instanceof PreconditionVerdict.Failed
        (verdict as PreconditionVerdict.Failed).failure().is(failure)
    }

    def "FR4: the plan's configuration refusal is a value too"() {
        when:
        def verdict = preconditions(BindingNames.CONTAINER, true, null)
                .verdict(DaemonConditionKind.CONTAINER_RUNTIME, CLONE, pipeline())

        then:
        (verdict as PreconditionVerdict.Failed).failure() instanceof UsageException
    }

    def "FR4: no image configured is a failed image verdict, with the port never asked"() {
        when:
        def verdict = preconditions(BindingNames.HOST, true, null)
                .verdict(DaemonConditionKind.IMAGE_CONTRACT, CLONE, pipeline())

        then:
        (verdict as PreconditionVerdict.Failed).failure().message == 'factory.sandbox.image'
        0 * readiness._
    }

    def "NFR-R1: the box git floor and the remote have no verdict, and nothing is asked: #kind"() {
        when:
        preconditions(BindingNames.CONTAINER, true).verdict(kind, CLONE, pipeline())

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains(fragment)
        0 * _

        where:
        kind | fragment
        DaemonConditionKind.BOX_GIT_FLOOR | 'container start'
        DaemonConditionKind.REMOTE | 'ls-remote'
    }

    def "FR1: the ref-format verdict needs no definition and asks the ref format alone: #label"() {
        given:
        branches.requireRefFormat(CLONE.clonePath()) >> {
            if (failure != null) {
                throw failure
            }
        }

        when:
        def verdict = preconditions(BindingNames.CONTAINER, false).refFormatVerdict(CLONE)

        then:
        0 * readiness._
        verdict == expected

        where:
        label | failure || expected
        'holds' | null || new PreconditionVerdict.Holds()
        'refused' | REF_REFUSAL || new PreconditionVerdict.Unmet(OperatorPreconditionRefusals.unmet(REF_REFUSAL).get())
        'unclassified' | FAULT || new PreconditionVerdict.Failed(FAULT)
    }

    private static final RuntimeException REF_REFUSAL = OperatorPreconditionRefusalSamples.of(DaemonConditionKind.CLONE_REF_FORMAT)
    private static final RuntimeException FAULT = new IllegalStateException('git died')
}
