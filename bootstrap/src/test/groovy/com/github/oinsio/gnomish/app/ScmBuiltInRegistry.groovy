package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.law.PipelineLaw
import com.github.oinsio.gnomish.adapter.scm.ScmConnectionReader
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.app.port.secrets.SecretsProvider
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Path
import java.time.Duration

/**
 * The run's {@link ExecutorRegistry} over a law whose checks name both SCM built-ins, for a spec
 * outside this package (M5 of migrate-verify-to-executor-contract): assembled the way run assembly
 * does — each connection admitted by {@link BuiltInConnections} from the operator's {@code
 * factory.check.<key>} subsections, the described law carrying them, the registry built over the
 * built-ins' entries. Both connections point at a loopback host and their tokens resolve; nothing
 * is contacted, since an entry binds its executor only at resolution.
 */
final class ScmBuiltInRegistry {

    private static final Map<String, Map<String, Object>> SUBSECTIONS = [
        'github-actions': [('api-url'): 'http://127.0.0.1:4000', repo: 'acme/tools'],
        'scm-status': [('api-url'): 'http://127.0.0.1:3000', repo: 'acme/widgets']
    ]

    private static final Map<String, String> TOKENS = [GNOMISH_GITHUB_ACTIONS_TOKEN: 't', GNOMISH_SCM_STATUS_TOKEN: 't']

    private ScmBuiltInRegistry() {}

    /** The check of {@code executor} the law names, in a fresh box. */
    static VerifyCheck.Executor check(String executor) {
        new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, executor, 'ci', [:], Duration.ofSeconds(1),
        Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.FRESH_BOX,
        Threshold.ERROR, ['build'])
    }

    /** The registry of a run whose law names {@code github-actions} and {@code scm-status}. */
    static ExecutorRegistry assemble() {
        def clock = new VirtualClock()
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [
            new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
            'i.md', [
                check(BuiltInExecutors.GITHUB_ACTIONS),
                check(BuiltInExecutors.SCM_STATUS)
            ], new AutonomyLimits(3), AdvancementMode.AUTO)
        ])
        def admission = new BuiltInConnections(SUBSECTIONS,
                new ScmConnectionReader(ConnectionProfiles.none(), new OperatorEgress(['loopback'])), { String name ->
                    Optional.ofNullable(TOKENS[name])
                } as SecretsProvider).admit(definition)
        def described = DescribedLaws.connected((admission as BuiltInConnections.Admission.Admitted).connections())
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, VirtualTimeEquipment.on(clock),
                TestChildEnvironment.hostAllowlist().programGrant(), Path.of('/tmp'), LastRegisteredCommit.NONE)
        ExecutorRegistry.of(described, definition, PipelineLaw.ofContent([:]), equipment,
        BuiltInEntriesFixture.of(clock, TOKENS), null)
    }
}
