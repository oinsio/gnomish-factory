package com.github.oinsio.gnomish.adapter.agent.program

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ChildEnvValues
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path

/**
 * What the program executor's specs build alike: a declaration over a program argv, its describe
 * (with or without {@code streams_progress}), the run's equipment and a {@code program} stage's
 * round request. Every value is the spec's; nothing here runs a process.
 */
final class ProgramFixtures {

    static final String NAME = 'acme'

    static final String KEY_VALUE = 'acme-secret-value'

    /** The credential source of a grant: ACME_API_KEY resolves, nothing else does. */
    static final ChildEnvValues SECRETS = { String name ->
        Optional.ofNullable([ACME_API_KEY: KEY_VALUE][name])
    } as ChildEnvValues

    private ProgramFixtures() {
    }

    static ExecutorDeclaration declaration(List<String> program, Map<String, Object> settings = [:]) {
        new ExecutorDeclaration(NAME, ExecutorDeclaration.Type.PROGRAM, program, settings, ['ACME_API_KEY'], [])
    }

    static Describe describe(List<Capability> capabilities = []) {
        new Describe(1, UntrustedText.subprocess('1'), [Role.TRANSFORM], capabilities,
        JsonNodeFactory.instance.objectNode(), ['ACME_API_KEY'], [], null, [], EnforcementLevel.PROMPT_ONLY, true)
    }

    static ProgramRoundEquipment equipment(Path stderrParent, ProgramBounds bounds = ProgramBounds.DEFAULTS,
            TimeEquipment time = VirtualTimeEquipment.create()) {
        new ProgramRoundEquipment(bounds, time, ChildEnvAllowlist.of([], [], SECRETS).programGrant(), stderrParent)
    }

    static StageDefinition stage(String model = '', Map<String, Object> settings = [:]) {
        new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.PROGRAM, model, settings, Sandbox.none(), NAME),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    static StageExecutor.Request round(Path workspace, StageDefinition stage = stage(), int attempt = 0,
            List feedback = []) {
        new StageExecutor.Request(
                new TaskContext('TASK-7', UntrustedText.tracker('title'), UntrustedText.tracker('body'), []),
                stage, new DirectoryWorkspace(workspace), attempt, feedback)
    }

    static ExecutorRequest request(Map<String, Object> settings = [:]) {
        ProgramRequests.of(round(Path.of('.'), stage('', settings)),
                com.github.oinsio.gnomish.adapter.law.PipelineLaw.ofContent(['instructions.md': 'build it\n']),
                declaration(['unused']), ProgramBounds.DEFAULTS)
    }
}
