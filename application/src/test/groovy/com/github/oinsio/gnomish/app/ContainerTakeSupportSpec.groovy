package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Path
import spock.lang.Specification

/**
 * {@link ContainerTakeSupport#hostOnly()}: the host-only bundle's selector resolves the explicit
 * host default whatever the pipeline, and its support factory is never built from (FR1 of
 * add-serve-sandbox-lifecycle; FR18 of supervise-daemon-loops-and-embed-dashboard, design D22 —
 * the bundle carries the root's selector, not its four inputs).
 */
class ContainerTakeSupportSpec extends Specification {

    private static final RegisteredClone CLONE =
    RegisteredCloneFixture.unregistered(Path.of('/srv/gnomish'), Path.of('/src/widgets'))

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition(
                    'a', 'purpose', [], [],
                    new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:], Sandbox.none()),
                    'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
    }

    def "hostOnly() plans every pipeline as a host run"() {
        expect:
        ContainerTakeSupport.hostOnly().modeSelector().plan(pipeline(), CLONE).mode() == SandboxModeSelector.Plan.Mode.HOST
    }

    def "hostOnly() never builds container support"() {
        when:
        ContainerTakeSupport.hostOnly().containerSupportFactory().create(Path.of('/c'), 'T-1', [], pipeline(), [])

        then:
        thrown(IllegalStateException)
    }
}
