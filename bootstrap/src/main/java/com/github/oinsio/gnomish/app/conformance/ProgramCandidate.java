package com.github.oinsio.gnomish.app.conformance;

import com.github.oinsio.gnomish.adapter.agent.program.PendingRuns;
import com.github.oinsio.gnomish.adapter.agent.program.PendingScope;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramExecutor;
import com.github.oinsio.gnomish.adapter.agent.program.ProgramRoundEquipment;
import com.github.oinsio.gnomish.app.describe.DescribeOutcome;
import com.github.oinsio.gnomish.app.describe.DescribePreflight;
import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.app.port.git.LastRegisteredCommit;
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.executorcontract.Describe;
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
 * WireObservation}; one {@code ProgramExecutor} for its {@code start}; and, for a pending case, a
 * second one for its polls, over a working copy of its own ({@link PollCopy}) and sharing the case's
 * table of pending runs, as one run of the engine holds one (design D11, D14 of
 * migrate-verify-to-executor-contract). The describe preflight runs once, over a synthetic law: one declaration named {@link
 * #NAME} carrying the argv and the pending settings, no credential, no egress, no stage.
 *
 * <p>Implements FR8, FR12, FR24 of define-executor-contract; FR11, FR12 of
 * migrate-verify-to-executor-contract.
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

    /** A program's ordinary cases send no settings; the command's options are unchanged. */
    @Override
    public Map<String, Object> settings() {
        return Map.of();
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
        boolean probe = kase == KitCase.UNKNOWN_REQUEST_FIELDS;
        // One table for the case, as one run of the engine holds one: start's executor and the poll's
        // share it, and no subject scopes it.
        var runs = new PendingRuns();
        return new ProgramCaseRun(
                side(workingCopy, describe, probe, runs),
                role -> side(
                        PollCopy.create(scratchRoot, kase, role, workingCopy, equipment.repositories()::init),
                        describe,
                        probe,
                        runs));
    }

    /** One side of a case's run: a {@code ProgramExecutor} over its own observed environment. */
    private ProgramCaseRun.Side side(Path workingCopy, Describe describe, boolean probe, PendingRuns runs) {
        WireObservation observation = new WireObservation(environment(workingCopy), probe);
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
                        equipment.stderrParent(),
                        // A candidate run has no round cell: no move of HEAD is the factory's.
                        LastRegisteredCommit.NONE),
                PendingScope.unscoped(runs));
        return new ProgramCaseRun.Side(executor, new Workspace(workingCopy.toString()), observation);
    }

    private HostTaskExecutionEnvironment environment(Path workingCopy) {
        return new HostTaskExecutionEnvironment(
                workingCopy, equipment.time().clock(), equipment.allowlist(), equipment.launch());
    }
}
