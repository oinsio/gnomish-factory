package com.github.oinsio.gnomish.app.conformance;

import com.github.oinsio.gnomish.adapter.agent.program.ProgramExecutor;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment;
import com.github.oinsio.gnomish.app.describe.DescribeOutcome;
import com.github.oinsio.gnomish.app.describe.DescribePreflight;
import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.Executor;
import com.github.oinsio.gnomish.executorcontract.Workspace;
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullMarked;

/**
 * A {@code program} candidate as the conformance kit drives it (FR12, FR24 of
 * define-executor-contract; design D2, D19): the declared argv, wrapped in the factory's own external
 * adapter, {@link ProgramExecutor}, so the kit spawns nothing itself and addresses the program
 * exactly as a stage would. Each case gets a directory of its own under the scratch root, named
 * after the case and made a git repository; one host environment over it, wrapped in a {@link
 * WireObservation}; and one {@code ProgramExecutor}, so the pending case starts and polls on one
 * instance. The describe preflight runs once, over a synthetic law: one declaration named {@link
 * #NAME} carrying the argv and the pending settings, no credential, no egress, no stage.
 *
 * <p>Implements FR8, FR12, FR24 of define-executor-contract.
 */
@NullMarked
public final class ProgramCandidate implements KitSubject {

    /** The name the synthetic law declares the candidate under. */
    static final String NAME = "candidate";

    private static final String BRANCH = "conformance";

    private final ExecutorDeclaration declaration;
    private final Path scratchRoot;
    private final KitEquipment equipment;

    /**
     * @param program the candidate's argv, the program first; never empty
     * @param settings the settings its declaration carries, the command's pending settings; never null
     * @param scratchRoot an existing, empty directory the cases' working copies are made in; never null
     * @param equipment what the candidate's runs are equipped with; never null
     */
    public ProgramCandidate(List<String> program, Map<String, ?> settings, Path scratchRoot, KitEquipment equipment) {
        this.declaration = new ExecutorDeclaration(
                NAME,
                ExecutorDeclaration.Type.PROGRAM,
                program,
                Map.<String, Object>copyOf(settings),
                List.of(),
                List.of());
        this.scratchRoot = scratchRoot;
        this.equipment = equipment;
    }

    @Override
    public boolean onWire() {
        return true;
    }

    @Override
    public Described describe() {
        WireObservation observation =
                new WireObservation(environment(CaseDirectories.create(scratchRoot, KitCase.DESCRIBE)), false);
        observation.materialize(BRANCH, null);
        try {
            var preflight = new DescribePreflight(
                    equipment.time().clock(),
                    ProgramBounds.DEFAULTS,
                    equipment.allowlist().programGrant());
            var law = new PipelineDefinition("1", new AutonomyLimits(1), List.of(), null, List.of(declaration));
            return switch (preflight.describe(observation, law)) {
                case DescribeOutcome.Described described ->
                    new Described(
                            described.answers().get(NAME),
                            observation.describeDocument(),
                            observation.breaches(),
                            List.of());
                case DescribeOutcome.Invalid invalid ->
                    new Described(
                            null,
                            observation.describeDocument(),
                            observation.breaches(),
                            invalid.errors().stream().map(ConfigError::message).toList());
            };
        } finally {
            observation.dispose();
        }
    }

    @Override
    public CaseRun open(KitCase kase, Describe describe) {
        Path workingCopy = CaseDirectories.create(scratchRoot, kase);
        equipment.repositories().init(workingCopy);
        WireObservation observation =
                new WireObservation(environment(workingCopy), kase == KitCase.UNKNOWN_REQUEST_FIELDS);
        observation.materialize(BRANCH, null);
        var executor = new ProgramExecutor(
                declaration,
                describe,
                List.of(),
                observation,
                new ProgramRoundEquipment(
                        ProgramBounds.DEFAULTS,
                        equipment.time(),
                        equipment.allowlist().programGrant(),
                        equipment.stderrParent()));
        return new ProgramRun(executor, new Workspace(workingCopy.toString()), observation);
    }

    private HostTaskExecutionEnvironment environment(Path workingCopy) {
        return new HostTaskExecutionEnvironment(
                workingCopy, equipment.time().clock(), equipment.allowlist(), equipment.launch());
    }

    /** One case's run of the program: its executor, its working copy, its observation. */
    private record ProgramRun(Executor executor, Workspace workspace, WireObservation observation) implements CaseRun {

        @Override
        public List<WireBreach> violations() {
            return observation.breaches();
        }

        @Override
        public void close() {
            observation.dispose();
        }
    }
}
