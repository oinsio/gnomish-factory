package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.EgressProperties
import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.program.DescribedProgram
import com.github.oinsio.gnomish.adapter.agent.program.PendingRuns
import com.github.oinsio.gnomish.adapter.agent.program.PendingScope
import com.github.oinsio.gnomish.adapter.agent.program.ProgramCheckBinding
import com.github.oinsio.gnomish.adapter.agent.program.ProgramExecutor
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.describe.DescribeOutcome
import com.github.oinsio.gnomish.app.describe.DescribePreflight
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.AttemptCommitWorkspace
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.Policy
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.RoleInputs
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.Workspace
import com.github.oinsio.gnomish.sandbox.AdapterBinding
import com.github.oinsio.gnomish.sandbox.BindingNames
import com.github.oinsio.gnomish.sandbox.CapabilityPassport
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ChildEnvValues
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.Segment
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.GuardImageAvailability
import com.github.oinsio.gnomish.subprocess.Drain
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The identity of design D10's {@code Describe} row (NFR-S1, NFR-C1 of define-executor-contract;
 * ADR 0017), on a real box: the credential names the law grants a {@code program} executor, agreed
 * by its describe in the box through the real preflight, are exactly the names its child receives —
 * as its own resolved layer through the run allowlist's grant, composed by the box like every exec —
 * and a sibling {@code command} check in the same box receives none of them. The box is built by the
 * production {@link ContainerRunSupportFactory} over the law, so its guard also admits the law's
 * executor egress host (FR10).
 *
 * <p>The {@code program} round is a real {@link ProgramExecutor} round in the box (task 7.1): the
 * declared program is the box's shell, which reports the names its child received and the key's
 * value in its result, read back through the one result reader. The value source is a fixed map, the
 * stand-in for the root's secrets port the allowlist is built over.
 *
 * <p>Its verdict half (NFR-S1, design D6 of migrate-verify-to-executor-contract, task 5.3): a {@code
 * program} verdict check bound through its production binding gets its token in the fresh box of its
 * request, whose guard admits its grant, and nowhere else; a credentialed built-in's token, resolved
 * in the factory process, reaches neither a fresh box nor the round box.
 *
 * <p>Docker- and guard-image-gated: skips cleanly with no daemon or no pullable mitmproxy image.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class ProgramCredentialDeliverySpec extends CredentialDeliverySpecBase {

    // Scenario: executor/result-channel / Declared credential reaches only its executor
    def "NFR-S1, NFR-C1: the names the law grants are the names the program child receives, and a sibling command check receives none"() {
        given: 'a law granting acme ACME_API_KEY and one egress host, and the box built for it'
        def law = ProgramInBox.law()
        def installation = ChildEnvAllowlist.of([], [], SECRETS)
        def support = startTask(law, installation)
        def box = support.lease().environmentFor('work')

        when: 'the preflight describes acme in the box'
        def outcome = new DescribePreflight(new VirtualClock(), ProgramBounds.DEFAULTS, installation.programGrant())
                .describe(box, law)

        then:
        outcome instanceof DescribeOutcome.Described
        def granted = (outcome as DescribeOutcome.Described).executors().credentials().of('acme')
        granted == ['ACME_API_KEY']

        when: 'a real ProgramExecutor round runs acme in the box with its grant; a sibling exec carries nothing'
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, ProgramInBox.realTime(), installation.programGrant(),
                Files.createDirectories(tempDir.resolve('stderr')), LastRegisteredCommit.NONE)
        def result = new ProgramExecutor(law.executors()[0], ProgramInBox.describe(), granted, box, equipment,
                PendingScope.unscoped(new PendingRuns())).start(ProgramInBox.request())
        def program = (result.reason().forLog().split(';')[0].split(',') as List).findAll {
            it
        } as Set
        def sibling = names(box, [:])

        then: 'the program child received exactly the granted names beyond what every child gets'
        result.status() == Status.FAILED
        (program - sibling - [
            'GNOMISH_RESULT_FILE',
            'GNOMISH_ANSWER_FILE'
        ]) == granted as Set
        !sibling.contains('ACME_API_KEY')

        and: 'with the value the source resolved'
        result.reason().forLog().split(';')[1] == VALUE

        when: 'a command check runs in the same box'
        def verdict = ShellCheckRounds.run(new ShellCommandCheckRunner(new VirtualClock())
                .withChildEnv(installation)
                .withEnvironments(support.pieces(null).checkEnvironments()),
                VerifyChecks.command('test -z "${ACME_API_KEY:-}"'), support.workspace())

        then: 'it passes only with the key absent from its process'
        ShellCheckRounds.answerOf(verdict) == new VerdictAnswer.Sarif([], null)

        and: 'FR10: the box guard admits the law\'s executor egress host beside the operator\'s'
        Files.readString(Path.of(System.getProperty('java.io.tmpdir'), 'gnomish-guard', taskId, 'allowlist.json')) ==
                '["example.com","api.acme.example"]'
    }

    // Scenario: verification/verdict-executors / Secret never enters the gnome's box
    // Scenario: verification/verdict-executors / A built-in's token enters no box
    def "NFR-S1, D6: a program verdict check's token reaches only its fresh box, and a built-in verdict check's token no box"() {
        given: 'a law whose stage is verified by acme in a fresh box and by github-actions, both tokens resolvable'
        def law = ProgramInBox.verdictLaw()
        def installation = ChildEnvAllowlist.of([], [], SECRETS)
        def support = startTask(law, installation)
        def round = support.lease().environmentFor('work')
        String head = gitOutput(project, 'rev-parse', "gnomish/${taskId}")
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, ProgramInBox.realTime(), installation.programGrant(),
                Files.createDirectories(tempDir.resolve('stderr')), LastRegisteredCommit.NONE)
        def binding = new ProgramCheckBinding(new DescribedProgram(law.executors()[0], ProgramInBox.describe(), ['ACME_API_KEY']),
        equipment, new PendingRuns(), support.pieces(null).freshBoxes())

        when: 'the program check\'s start runs in the box its binding materialized at the attempt commit'
        def bound = binding.bind(CheckRef.of(0, law.stages()[0].verify()[0]), {
            -> head
        } as AttemptCommitWorkspace)
        def grantedGuard = Files.readString(guardFile('-v'))
        def result = bound.executor().start(ProgramInBox.verdict())
        bound.release().run()
        def program = (result.reason().forLog().split(';')[0].split(',') as List).findAll {
            it
        } as Set

        then: 'its child received its token with the value the source resolved, and its box admitted its grant'
        program.contains('ACME_API_KEY')
        result.reason().forLog().split(';')[1] == VALUE
        grantedGuard == '["example.com","api.acme.example"]'

        when: 'a built-in check\'s fresh box, and the round box'
        def fresh = support.pieces(null).freshBoxes().freshBox(head, [])
        def inFresh = names(fresh.environment(), [:])
        fresh.close()
        def inRound = names(round, [:])

        then: 'neither token reaches either box: the built-in\'s is resolved in the factory process only'
        !inFresh.contains('GNOMISH_GITHUB_ACTIONS_TOKEN') && !inFresh.contains('ACME_API_KEY')
        !inRound.contains('GNOMISH_GITHUB_ACTIONS_TOKEN') && !inRound.contains('ACME_API_KEY')
        support.environments.scrubsCredential('GNOMISH_GITHUB_ACTIONS_TOKEN')
    }
}
