package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.describe.DescribeOutcome;
import com.github.oinsio.gnomish.app.describe.DescribePreflight;
import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment;
import com.github.oinsio.gnomish.subprocess.ProcessLaunch;
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
 * <p>The only production file that constructs a {@link DescribedLaw} ({@code DescribedLawOwnerSpec}).
 *
 * <p>Implements FR4, FR13, NFR-S1, UX1 of define-executor-contract.
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
        if (definition.executors().isEmpty()) {
            return new DescribedLaw(lawBinding, Map.of());
        }
        // The run's own allowlist, as the run's checks and agent rounds compose from it: its grant
        // judges every credential the law grants against the shared layers (NFR-S1, task 6.4).
        ChildEnvAllowlist allowlist = assembly.checks.childEnv(
                assembly.sandboxProperties.envPassthrough(), definition, credentialEnvVarsToScrub);
        var preflight = new DescribePreflight(assembly.time.clock(), ProgramBounds.DEFAULTS, allowlist.programGrant());
        DescribeOutcome outcome =
                switch (medium) {
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
        return switch (outcome) {
            case DescribeOutcome.Described(var answers) -> new DescribedLaw(lawBinding, answers);
            case DescribeOutcome.Invalid(var errors) -> new LawDescription.Refused(errors);
        };
    }
}
