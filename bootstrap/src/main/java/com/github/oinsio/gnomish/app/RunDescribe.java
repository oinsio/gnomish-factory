package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.agent.program.ProgramCheckPlacement;
import com.github.oinsio.gnomish.app.describe.DescribeOutcome;
import com.github.oinsio.gnomish.app.describe.DescribePreflight;
import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment;
import com.github.oinsio.gnomish.subprocess.ProcessLaunch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one owner of a run's described law (design D5 of define-executor-contract, as revised
 * 2026-10-31), behind {@link RunAssembly#describe}: it opens the task's live execution environment
 * for the medium — a host environment over the run's directory, composed through the run's own
 * child-environment allowlist and disposed after; or the task's box, which stays live for the first
 * round — runs the describe preflight there, and builds the {@link DescribedLaw} every {@link
 * RunAssembly#assemble} takes. A law that declares no executor is described with no environment, so
 * no run that names no program pays for one; nor is a run past the pipeline's end, which runs no
 * round.
 *
 * <p>Before the describe branch decides, the connections of the SCM built-ins the law's checks name
 * are admitted ({@link BuiltInConnections}; design D18 of migrate-verify-to-executor-contract, "Where
 * a refusal at run assembly goes"): a refusal joins the describe's located errors in one {@link
 * LawDescription.Refused}, so {@code take} and {@code serve} park the task and {@code run} fails as an
 * invalid law, and the admitted connections travel to the registry assembly with the described law.
 * A check naming one of the law's programs where no fresh box can bind it — host mode, or a {@code
 * same-box} check in a box — is refused on the same path ({@link ProgramCheckPlacement}; D18, "A
 * {@code program} check outside a fresh box"), so it never reaches a round as a wiring defect.
 *
 * <p>The only production file that constructs a {@link DescribedLaw} ({@code DescribedLawOwnerSpec}).
 *
 * <p>Implements FR4, FR13, NFR-S1, UX1 of define-executor-contract; FR1, FR3, FR13, NFR-S2 of
 * migrate-verify-to-executor-contract.
 */
final class RunDescribe {

    private RunDescribe() {}

    static LawDescription describe(
            ManualRunAssembly assembly,
            RunOrder order,
            DescribeMedium medium,
            List<String> credentialEnvVarsToScrub,
            LawBinding lawBinding) {
        PipelineDefinition definition = order.definition();
        // Design D18 of migrate-verify-to-executor-contract: the SCM built-ins' connections are read
        // and admitted before the describe branch decides, and a refusal takes the describe
        // refusal's path. A run that runs no stage runs no check, so it admits none.
        BuiltInConnections.Admission admission = medium instanceof DescribeMedium.NoStageRuns
                ? new BuiltInConnections.Admission.Admitted(Map.of())
                : assembly.checks.connections().admit(definition);
        // A program check no fresh box can bind refuses the law before any process of it is
        // spawned, its describe included (D18, "A program check outside a fresh box").
        List<ConfigError> errors = new ArrayList<>(
                medium instanceof DescribeMedium.NoStageRuns
                        ? List.of()
                        : ProgramCheckPlacement.refusals(definition, medium instanceof DescribeMedium.Box));
        DescribeOutcome outcome = definition.executors().isEmpty() || !errors.isEmpty()
                ? new DescribeOutcome.Described(Map.of())
                : preflight(assembly, definition, medium, credentialEnvVarsToScrub);
        if (outcome instanceof DescribeOutcome.Invalid(var invalid)) {
            errors.addAll(invalid);
        }
        if (admission instanceof BuiltInConnections.Admission.Refused(var refused)) {
            errors.addAll(refused);
        }
        return errors.isEmpty()
                        && admission instanceof BuiltInConnections.Admission.Admitted(var connections)
                        && outcome instanceof DescribeOutcome.Described(var answers)
                ? new DescribedLaw(lawBinding, answers, connections)
                : new LawDescription.Refused(errors);
    }

    private static DescribeOutcome preflight(
            ManualRunAssembly assembly,
            PipelineDefinition definition,
            DescribeMedium medium,
            List<String> credentialEnvVarsToScrub) {
        // The run's own allowlist, as the run's checks and agent rounds compose from it: its grant
        // judges every credential the law grants against the shared layers (NFR-S1, task 6.4).
        ChildEnvAllowlist allowlist = assembly.checks.childEnv(
                assembly.sandboxProperties.envPassthrough(), definition, credentialEnvVarsToScrub);
        var preflight = new DescribePreflight(assembly.time.clock(), ProgramBounds.DEFAULTS, allowlist.programGrant());
        return switch (medium) {
            case DescribeMedium.NoStageRuns ignored -> new DescribeOutcome.Described(Map.of());
            case DescribeMedium.Box(var support, var stage) ->
                preflight.describe(support.liveEnvironment(stage), definition);
            case DescribeMedium.Host(var root) -> {
                TaskExecutionEnvironment environment = new HostTaskExecutionEnvironment(
                        root, assembly.time.clock(), allowlist, ProcessLaunch.grouped());
                environment.materialize("workspace:" + root.getFileName(), null);
                try {
                    yield preflight.describe(environment, definition);
                } finally {
                    environment.dispose();
                }
            }
        };
    }
}
