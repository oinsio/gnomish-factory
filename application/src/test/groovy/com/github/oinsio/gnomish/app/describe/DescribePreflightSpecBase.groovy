package com.github.oinsio.gnomish.app.describe

import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.ArtifactInput
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.Sandbox
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The fixture of the describe preflight specs (FR4, FR9, FR13 of define-executor-contract): a real
 * host environment, the executor stand-in presets of design D18, and a law built from them.
 */
abstract class DescribePreflightSpecBase extends Specification {

    @TempDir
    Path tempDir

    HostTaskExecutionEnvironment environment

    def setup() {
        Files.createDirectories(tempDir.resolve('work'))
        environment = new HostTaskExecutionEnvironment(
                tempDir.resolve('work'), new VirtualClock(), TestChildEnvironment.hostAllowlist(), ProcessLaunch.grouped())
        environment.materialize('describe-spec', null)
    }

    def cleanup() {
        environment.dispose()
    }

    protected static ConfigError only(DescribeOutcome outcome) {
        assert outcome instanceof DescribeOutcome.Invalid
        def errors = ((DescribeOutcome.Invalid) outcome).errors()
        assert errors.size() == 1
        errors.first()
    }

    protected static DescribePreflight preflight() {
        preflight(ChildEnvAllowlist.none())
    }

    /** The preflight judging granted credentials against {@code allowlist}'s shared layers and source. */
    protected static DescribePreflight preflight(ChildEnvAllowlist allowlist) {
        new DescribePreflight(new VirtualClock(), ProgramBounds.DEFAULTS, allowlist.programGrant())
    }

    protected Path describeAs(String answer) {
        StandIn.link(tempDir.resolve(answer), 'describe-as')
    }

    protected static PipelineDefinition law(Map<String, Path> programs, StageDefinition... stages) {
        law(programs, [:], stages)
    }

    protected static PipelineDefinition law(Map<String, Path> programs, Map<String, Map> settings, StageDefinition... stages) {
        def declarations = programs.collect { name, program ->
            new ExecutorDeclaration(name, ExecutorDeclaration.Type.PROGRAM, [program.toString()], settings.get(name, [:]), [], [])
        }
        new PipelineDefinition('1', new AutonomyLimits(2), stages.toList(), null, declarations)
    }

    protected static StageDefinition stage(String name, String executor) {
        new StageDefinition(name, "Purpose of $name", [new ArtifactInput.Source()], [],
        new StageDefinition.Executor(executor == null ? ExecutorType.AGENT_CLI : ExecutorType.PROGRAM, '', [:], Sandbox.none(), executor),
        "stages/$name/instructions.md", [], new AutonomyLimits(2), AdvancementMode.AUTO)
    }

    /** An executor check {@code sonar/gate} of {@code token}'s form naming {@code executor}. */
    protected static VerifyCheck.Executor check(VerifyCheck.Token token, String executor) {
        new VerifyCheck.Executor(token, executor, 'sonar/gate', [:], Duration.ofSeconds(5), Duration.ofMinutes(5),
        VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.FRESH_BOX, Threshold.DEFAULT, ['sonar/gate'])
    }

    /** A stage {@code build} run by an agent CLI whose verify list is {@code checks}. */
    protected static StageDefinition verifying(VerifyCheck... checks) {
        new StageDefinition('build', 'Purpose of build', [new ArtifactInput.Source()], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, '', [:], Sandbox.none(), null),
        'stages/build/instructions.md', checks.toList(), new AutonomyLimits(2), AdvancementMode.AUTO)
    }
}
