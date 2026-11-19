package com.github.oinsio.gnomish.app.killpoint

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment
import com.github.oinsio.gnomish.adapter.git.LocalBoxEnvironment
import com.github.oinsio.gnomish.app.BuiltInEntriesFixture
import com.github.oinsio.gnomish.app.CheckDispatchFixture
import com.github.oinsio.gnomish.app.DescribedLaws
import com.github.oinsio.gnomish.app.describe.ProgramBounds
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer
import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.app.port.check.FreshBoxSource
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * A pollable {@code program} verdict check on the container medium without a daemon (task 5.3 of
 * migrate-verify-to-executor-contract; design D11 as amended 2026-11-14, D14): the law declares the
 * {@code verdict-scripted} stand-in as its one executor, its check verifies {@code fresh-box}, and the
 * pickup's check dispatch binds it through the production registry entry with a fresh box per request
 * — each a {@link LocalBoxEnvironment} seeded from the factory clone and put on the commit the
 * binding names. The program answers the script a row writes and records, per request, its operation,
 * key, {@code HEAD} and working copy, so a row asserts what each box stood on and that no {@code start}
 * was sent.
 */
final class ContainerProgramChecks {

    static final String NAME = 'judge-bot'

    final Path root
    final Path link

    /** The grant each box was built for, in request order. */
    final List<List<String>> grants = []

    /** The boxes released at their request's end. */
    int released

    ContainerProgramChecks(Path root) {
        this.root = root
        this.link = StandIn.recording(Files.createDirectories(root.resolve('bin')), 'verdict-scripted')
    }

    /** The fresh-box check of the law's program. */
    VerifyCheck.Executor check() {
        new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, NAME, 'judge', [:], Duration.ofSeconds(30),
        Duration.ofMinutes(10), VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.FRESH_BOX, Threshold.ERROR, [])
    }

    /** One automatic stage {@code build}, verified by the program's check; the program declared and granted one host. */
    PipelineDefinition law() {
        def stage = new StageDefinition('build', 'purpose', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
        'instructions.md', [check()], new AutonomyLimits(1), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(1), [stage], null, [
            new ExecutorDeclaration(NAME, ExecutorDeclaration.Type.PROGRAM, [link.toString()], [:], [], ['api.judge.example'])
        ])
    }

    /** The program answers, in order: {@code pending:<key>} for a pending run under that key, {@code verdict} for its end. */
    void script(List<String> answers) {
        Files.writeString(StandIn.beside(link, 'script'), answers.collect { String answer ->
            answer == 'verdict' ? StandIn.data('executor-result#verdict')
            : StandIn.data('executor-result#pending').replace('reference-deferred', answer - 'pending:')
        }*.trim().join('\n') + '\n')
    }

    /** What each request saw: {@code [operation, key or -, HEAD, working copy]}, in order. */
    List<List<String>> requests() {
        Path heads = StandIn.beside(link, 'heads')
        Files.exists(heads) ? heads.toFile().readLines()*.split(' ')*.toList() : []
    }

    /** {@code resume} as the engine's one port, the program's checks bound through this world's fresh boxes. */
    StageExecutor port(StageExecutor resume, TakeKillPointWorld world, CurrentRound cell) {
        // The program's clock stands past the local box's fixed exec stamp, so a run's wall time is positive.
        def time = VirtualTimeEquipment.on(new VirtualClock(LocalBoxEnvironment.EXEC_STAMP.plusSeconds(3600)))
        def equipment = new ProgramRoundEquipment(ProgramBounds.DEFAULTS, time,
                TestChildEnvironment.hostAllowlist().programGrant(), Files.createTempDirectory(root, 'stderr'), cell)
        CheckDispatchFixture.routedBoxed(resume, DescribedLaws.described([(NAME): describe()]), world.definition,
        world.repoDir, BuiltInEntriesFixture.of(new VirtualClock()), equipment, boxes(world))
    }

    private FreshBoxSource boxes(TakeKillPointWorld world) {
        { String commit, List<String> egress ->
            def box = new LocalBoxEnvironment(world.repoDir, Files.createTempDirectory(root, 'fresh'))
            box.materialize(TaskIdSanitizer.branchName(world.taskId), commit)
            grants << egress
            new CheckEnvironmentSource.Acquired() {
                        TaskExecutionEnvironment environment() {
                            box
                        }

                        void close() {
                            released++
                            box.dispose()
                        }
                    }
        } as FreshBoxSource
    }

    private static Describe describe() {
        new Describe(1, UntrustedText.subprocess('1'), [Role.VERDICT], [Capability.POLL],
        JsonNodeFactory.instance.objectNode(), [], [], null, [], EnforcementLevel.PROMPT_ONLY, true, false)
    }
}
