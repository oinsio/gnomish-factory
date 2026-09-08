package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.TaskRepository;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.baseref.AllowedBases;
import com.github.oinsio.gnomish.baseref.BaseDecision;
import com.github.oinsio.gnomish.baseref.BaseDesignator;
import com.github.oinsio.gnomish.baseref.BaseRefRequest;
import com.github.oinsio.gnomish.baseref.BaseRefResolver;
import com.github.oinsio.gnomish.baseref.BaseResolution;
import com.github.oinsio.gnomish.baseref.ResolutionMode;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The task-creation helper {@link GitModeRunner} needs on the fresh-run path — split out purely to
 * keep {@link GitModeRunner} within the project's file-size guidance
 * (`.claude/rules/process-invariants.md`); the behavior is unchanged from what {@link
 * GitModeRunner} used to do inline. The state/task readbacks that used to live here moved onto the
 * {@link com.github.oinsio.gnomish.app.port.git.TaskStoreGit} port (FR12b, design D12 of
 * split-into-modules): they parsed the git adapter's own file layout, which no {@code application}
 * class may know.
 *
 * <p>Implements FR6, FR7 of add-git-workflow; FR4, FR10 of add-base-ref-resolution.
 */
final class GitFreshTaskSupport {

    private GitFreshTaskSupport() {}

    /**
     * Delegates to {@link TaskRepository#createTask} — the sole branch/worktree creator on the
     * fresh-run path — remapping its {@link GitTaskRepositoryException} to a {@link
     * UsageException} (exit code 2): on a fresh run, both causes {@code createTask} can throw (an
     * already-existing branch for this taskId, or an unresolved {@code --base}) name an operator
     * mistake, not a resumable condition.
     *
     * <p>{@code decision} is resolved by the caller: a manual-run caller with only a raw {@code
     * --base} string in hand resolves one first through {@link #resolveManualBase}, while a
     * take/serve caller that already ran {@code FreshClaimBaseBinding} passes its own {@link
     * BaseDecision} straight through — this method never re-resolves a ref it is handed, which
     * matters because a second MANUAL-mode resolve of an already-resolved ref would report it back
     * under {@link com.github.oinsio.gnomish.baseref.BaseRule#EXPLICIT_ARGUMENT} regardless of the
     * tier that actually produced it (FR7 of add-base-ref-resolution: the pin records the REAL
     * rule, not an artifact of how this method is called).
     *
     * <p>The ref and the rule are pinned into the task-creation commit by {@code taskRepository}
     * itself (FR7); this method only threads the already-resolved {@link BaseDecision} through.
     *
     * <p>{@code initialState} is the state the caller synthesized from the frozen pipeline law,
     * recorded in the STARTED commit beside the context (FR3, design D2 of
     * harden-task-branch-contract) so a first-round crash still leaves a readable branch.
     */
    static void createTask(
            TaskRepository taskRepository,
            String taskId,
            TaskContext context,
            BaseDecision decision,
            TaskState initialState) {
        try {
            taskRepository.createTask(context, decision.ref(), decision.rule(), initialState);
        } catch (GitTaskRepositoryException e) {
            throw new UsageException("could not start git-mode task \"" + taskId + "\": " + e.getMessage()
                    + " — this is a fresh run, not --resume; pick a different --task-id, fix --base, or resume the"
                    + " existing task instead");
        }
    }

    /**
     * Resolves a manual-run {@code --base} argument (or its absence) to a {@link BaseDecision}
     * through {@link BaseRefResolver} rather than a hand-rolled default: an absent designator and
     * no allowed-base tier apply at this call site (the caller has no tracker task and no
     * trusted-tier config in hand — that richer input is {@code FreshClaimBaseBinding}'s job for
     * the take/serve paths), and {@link ResolutionMode#MANUAL} guarantees resolution always answers
     * {@link BaseResolution.Resolved}: an explicit {@code --base} wins outright, otherwise it falls
     * through to {@link BaseRefResolver#LOCAL_HEAD_REF}, the clone's current {@code HEAD} —
     * reproducing the previous ad-hoc default exactly, now through the one real policy component
     * instead of a duplicated literal.
     */
    static BaseDecision resolveManualBase(@Nullable String base) {
        BaseRefRequest request = new BaseRefRequest(
                base, BaseDesignator.absent(), AllowedBases.empty(), null, ResolutionMode.MANUAL, null);
        return requireResolved(BaseRefResolver.resolve(request), GitFreshTaskSupport::unreachableOnManualPath);
    }

    /**
     * Builds the exception for the one arm {@link #resolveManualBase} can never actually take: at
     * this call site resolution is structurally always {@link BaseResolution.Resolved} (an absent
     * designator abstains, there is no configured default, and {@link ResolutionMode#MANUAL}
     * guarantees the {@link BaseRefResolver#LOCAL_HEAD_REF} fallback). Named and package-private
     * rather than inlined as a lambda in {@link #resolveManualBase} so a spec can invoke this exact
     * method directly with a hand-built {@link BaseResolution.Underdetermined} — an inline lambda
     * here would itself be dead code no test could reach, the same coverage trap one layer up.
     */
    static RuntimeException unreachableOnManualPath(BaseResolution.Underdetermined underdetermined) {
        return new IllegalStateException(
                "base ref resolution was underdetermined on a manual fresh-run path, which should never happen: "
                        + underdetermined.reason());
    }

    /**
     * Maps any {@link BaseResolution} to its resolved {@link BaseDecision}, or throws the exception
     * {@code onUnderdetermined} builds from the refusal. Extracted so both branches are
     * independently testable: at this call site resolution is structurally always {@link
     * BaseResolution.Resolved}, so the {@link BaseResolution.Underdetermined} arm is unreachable
     * from {@link #resolveManualBase} itself — a unit spec exercises it directly with a hand-built
     * value instead.
     */
    static BaseDecision requireResolved(
            BaseResolution resolution, Function<BaseResolution.Underdetermined, RuntimeException> onUnderdetermined) {
        return switch (resolution) {
            case BaseResolution.Resolved resolved -> resolved.decision();
            case BaseResolution.Underdetermined underdetermined -> throw onUnderdetermined.apply(underdetermined);
        };
    }
}
