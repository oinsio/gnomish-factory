package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.InstantSource

/**
 * The {@code program} executor {@code ProgramCredentialDeliverySpec} runs in a real box: a law
 * declaring {@code acme} over the box's own shell, which answers both operations itself — its
 * describe, and a start that reports the variable names its child received and the key's value in
 * its result's reason — with the describe the preflight agrees with and a request to start it with.
 */
final class ProgramInBox {

    private ProgramInBox() {
    }

    /** A describe written by the declared program itself: the box's shell, no stand-in. */
    static final String DESCRIBE = '{"protocolVersion":1,"version":"1","roles":["transform"],"capabilities":[],' +
    '"settingsSchema":{},"credentials":["ACME_API_KEY"],"egress":["api.acme.example"],"policyDisablingFlags":[],' +
    '"enforcementLevel":"PROMPT_ONLY","reportsUsage":false}'

    /** describe writes its answer; start reports the names it received and the key's value. */
    static final String START_OR_DESCRIBE = "case \"\$1\" in describe) printf '%s' '${DESCRIBE}' > \"\$GNOMISH_RESULT_FILE\";; " +
    "start) names=\$(env | cut -d= -f1 | tr '\\n' ','); printf '{\"status\":\"failed\",\"failureClass\":\"quality\"," +
    "\"reason\":\"%s;%s\",\"usage\":{\"provenance\":\"ABSENT\"}}' \"\$names\" \"\$ACME_API_KEY\" > \"\$GNOMISH_RESULT_FILE\";; esac"

    static PipelineDefinition law() {
        def stage = new StageDefinition('work', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.PROGRAM, 'model-x', [:], Sandbox.none(), 'acme'),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        // The program answers both operations itself; the operation word arrives as $1. Its start
        // reports the variable names it received and the key's value in its result's reason, a
        // failed/quality the spec reads back through the one result reader.
        def program = [
            'sh',
            '-c',
            START_OR_DESCRIBE,
            'acme'
        ]
        new PipelineDefinition('1', new AutonomyLimits(3), [stage], null, [
            new ExecutorDeclaration('acme', ExecutorDeclaration.Type.PROGRAM, program, [:], ['ACME_API_KEY'], ['api.acme.example'])
        ])
    }

    static Describe describe() {
        new Describe(1, UntrustedText.subprocess('1'), [Role.TRANSFORM], [], JsonNodeFactory.instance.objectNode(),
        ['ACME_API_KEY'], ['api.acme.example'], null, [], EnforcementLevel.PROMPT_ONLY, false)
    }

    static ExecutorRequest request() {
        new ExecutorRequest(Role.TRANSFORM, new Workspace('.'), new Policy.WorkspaceWrite(), RoleInputs.transform('build it'),
                JsonNodeFactory.instance.objectNode(), ProgramBounds.DEFAULTS.hints(), [], null)
    }

    static TimeEquipment realTime() {
        // real-time-wiring: the box stamps an exec's start on the wall clock its fixture runs on, and
        //     the program's wall time is measured against it; the time source is not the subject here.
        new TimeEquipment(InstantSource.system(), new ThreadSleeper())
    }
}
