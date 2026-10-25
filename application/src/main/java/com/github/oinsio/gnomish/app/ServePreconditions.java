package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.TaskBranchGit;
import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.app.serve.PreconditionRecheck;
import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code serve} daemon's startup verdict on the static operator preconditions (design D17 of
 * add-sandbox-base-image): the checks a slot meets after its claim, run once before the first
 * claim, in the order a slot meets them — the factory clone's ref format, the sandbox plan, the
 * image contract and the box's git floor (the last two only for a boxed pipeline). The first
 * refusal is the verdict; nothing is written to the tracker, the clone or the runtime but the
 * floor probe's one helper container.
 *
 * <p>The owner adds no spelling of any check: it takes ports only — {@link TaskBranchGit} for the
 * ref format, the {@link SandboxModeSelector} for the plan, {@link SandboxImageReadiness} for the
 * image — never a git or docker runner ({@code ServePreconditionSpellingSpec} in {@code
 * :bootstrap}). What counts as a refusal is {@link OperatorPreconditionRefusals}' answer; the
 * plan's configuration refusals ({@link UsageException}) and any unclassified failure propagate
 * unchanged, as they would from a slot.
 *
 * <p>Implements FR13, NFR-R3, UX3 of add-sandbox-base-image.
 */
final class ServePreconditions {

    /** The verdict: every precondition holds, or the first one that did not. */
    sealed interface StartupAssessment {

        /** Every precondition holds; startup continues. */
        record Pass() implements StartupAssessment {}

        /**
         * A precondition refused the daemon.
         *
         * @param refusal the check's own typed refusal, whose message is the operator's line
         * @param kind the precondition it refuses
         */
        record Refused(RuntimeException refusal, DaemonConditionKind kind) implements StartupAssessment {}
    }

    private final TaskBranchGit branches;
    private final SandboxModeSelector modeSelector;
    private final SandboxImageReadiness imageReadiness;
    private final SandboxProperties sandbox;

    /**
     * @param branches the ref-format check's port; never null
     * @param modeSelector the process's one execution-mode selector; never null
     * @param imageReadiness the image contract and box git floor checks' port; never null
     * @param sandbox the operator sandbox config, read for the configured image; never null
     */
    ServePreconditions(
            TaskBranchGit branches,
            SandboxModeSelector modeSelector,
            SandboxImageReadiness imageReadiness,
            SandboxProperties sandbox) {
        this.branches = branches;
        this.modeSelector = modeSelector;
        this.imageReadiness = imageReadiness;
        this.sandbox = sandbox;
    }

    /**
     * Assesses the daemon's preconditions against its clone and its bound definition.
     *
     * @param clone the daemon's registered clone; never null
     * @param definition the definition the daemon bound at startup; never null
     * @return pass, or the first refusal; never null
     * @throws UsageException when the plan refuses the configuration
     */
    StartupAssessment assess(RegisteredClone clone, PipelineDefinition definition) {
        try {
            check(DaemonConditionKind.CLONE_REF_FORMAT, clone, definition);
            if (modeSelector.runsInBox(definition, clone)) {
                check(DaemonConditionKind.IMAGE_CONTRACT, clone, definition);
            }
            return new StartupAssessment.Pass();
        } catch (RuntimeException failure) {
            Optional<DaemonConditionKind> kind = OperatorPreconditionRefusals.kindOf(failure);
            if (kind.isEmpty()) {
                throw failure;
            }
            return new StartupAssessment.Refused(failure, kind.get());
        }
    }

    /**
     * The same checks bound to the daemon's clone and definition, for its conditions' probes
     * (design D19): each kind runs the check the assessment runs for it, so startup and probe are
     * one spelling.
     *
     * @param clone the daemon's registered clone; never null
     * @param definition the definition the daemon bound at startup; never null
     * @return the bound checks; never null
     */
    PreconditionRecheck rechecks(RegisteredClone clone, PipelineDefinition definition) {
        return kind -> {
            try {
                check(kind, clone, definition);
                return Optional.empty();
            } catch (RuntimeException failure) {
                return Optional.of(OperatorPreconditionRefusals.unmet(failure).orElseThrow(() -> failure));
            }
        };
    }

    /**
     * Runs the check that owns {@code kind}: the ref format, the plan (which meets the runtime
     * first), or the image's contract and git floor, which one inspection judges together.
     */
    private void check(DaemonConditionKind kind, RegisteredClone clone, PipelineDefinition definition) {
        switch (kind) {
            case CLONE_REF_FORMAT -> branches.requireRefFormat(clone.clonePath());
            case CONTAINER_RUNTIME -> modeSelector.runsInBox(definition, clone);
            // A boxed plan has already refused a container run with no image configured.
            case IMAGE_CONTRACT, BOX_GIT_FLOOR ->
                imageReadiness.assess(Objects.requireNonNull(sandbox.image(), "factory.sandbox.image"));
            case REMOTE -> throw new IllegalArgumentException("the remote is probed by its own ls-remote");
        }
    }
}
