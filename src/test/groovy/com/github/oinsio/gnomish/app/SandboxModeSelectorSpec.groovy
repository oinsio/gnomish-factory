package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.BindingProperties
import com.github.oinsio.gnomish.SandboxProperties
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.util.function.BooleanSupplier
import spock.lang.Specification

/**
 * FR14, G2, UX2, D13 of add-sandbox-core (the integration pass): the git-mode
 * run-shape selector — host only when the operator names it, fail-closed
 * refusals for unmet needs, mixed bindings, and missing container
 * prerequisites, each with one clear error naming the way out.
 */
class SandboxModeSelectorSpec extends Specification {

    private static StageDefinition stage(String name, Sandbox sandbox = Sandbox.none()) {
        new StageDefinition(
                name, 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:], sandbox),
                'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline(StageDefinition... stages) {
        new PipelineDefinition('1', new AutonomyLimits(3), stages as List)
    }

    private static SandboxProperties sandbox(String image) {
        new SandboxProperties(image, null, null, null, [], [], false)
    }

    def "an explicit host default plans a host run with one segment"() {
        when:
        def plan = SandboxModeSelector.plan(
                pipeline(stage('a'), stage('b')), new BindingProperties('host', [:]), sandbox(null))

        then:
        plan.mode() == SandboxModeSelector.Plan.Mode.HOST
        plan.segments().size() == 1
    }

    def "D13: the container default without an image refuses naming both ways out, never silent host"() {
        when:
        SandboxModeSelector.plan(pipeline(stage('a')), new BindingProperties(null, [:]), sandbox(null))

        then:
        def e = thrown(UsageException)
        e.message.contains('factory.sandbox.image')
        e.message.contains('factory.bindings.default=host')
    }

    def "FR14/UX2: an unmet stage need refuses fail-closed naming the stage and the need"() {
        given: 'a host-bound stage declaring a need only the container passport satisfies'
        def needy = stage('a', new Sandbox(['egress-control'], false))

        when:
        SandboxModeSelector.plan(pipeline(needy), new BindingProperties('host', [:]), sandbox(null))

        then:
        def e = thrown(UsageException)
        e.message.contains('"a"')
        e.message.contains('egress-control')
    }

    def "mixed host and container bindings within one pipeline are refused honestly"() {
        when:
        SandboxModeSelector.plan(
                pipeline(stage('a'), stage('b')),
                new BindingProperties('host', [b: 'container']),
                sandbox('img:1'))

        then:
        def e = thrown(UsageException)
        e.message.contains('mixed host/container')
    }

    def "an unknown binding name is a usage error naming the configuration"() {
        when:
        SandboxModeSelector.plan(pipeline(stage('a')), new BindingProperties('vm', [:]), sandbox(null))

        then:
        def e = thrown(UsageException)
        e.message.contains('factory.bindings')
    }

    // G2, D13: with both prerequisites met (image + reachable runtime, scripted probe) the default
    // bindings plan a CONTAINER run over the planned segments — a real plan, never null.
    def "the container default with an image and a reachable runtime plans a container run"() {
        when:
        def plan = SandboxModeSelector.plan(
                pipeline(stage('a'), stage('b')),
                new BindingProperties(null, [:]),
                sandbox('img:1'),
                { true } as BooleanSupplier)

        then:
        plan.mode() == SandboxModeSelector.Plan.Mode.CONTAINER
        plan.segments().size() == 1
    }

    // D13: a blank (whitespace-only) image is as absent as a null one — same fail-closed refusal.
    def "D13: a blank container image refuses naming factory.sandbox.image, never silent host"() {
        when:
        SandboxModeSelector.plan(
                pipeline(stage('a')), new BindingProperties(null, [:]), sandbox('   '),
                { true } as BooleanSupplier)

        then:
        def e = thrown(UsageException)
        e.message.contains('factory.sandbox.image')
    }

    // D13, G2: an unreachable Docker runtime refuses naming both ways out — never a host fallback.
    def "D13: an unreachable Docker runtime refuses naming both ways out"() {
        when:
        SandboxModeSelector.plan(
                pipeline(stage('a')), new BindingProperties(null, [:]), sandbox('img:1'),
                { false } as BooleanSupplier)

        then:
        def e = thrown(UsageException)
        e.message.contains('Docker runtime is unreachable')
        e.message.contains('factory.bindings.default=host')
    }
}
