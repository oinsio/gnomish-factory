package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.BaseRefKind;
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome;
import com.github.oinsio.gnomish.subprocess.Termination;
import java.nio.file.Path;

/**
 * Turns one completed refresh fetch and its destination ref into an outcome, under the rule that
 * makes the refresh fail-closed: <em>both</em> the fetch and the ref must answer.
 *
 * <p>Neither half is sufficient on its own, and the asymmetry with {@link TaskBranchLocator} — where
 * an already-present ref is a perfectly good answer — is the point. The locator only needs <em>a</em>
 * branch; a refresh needs a <em>fresh</em> one. A clone that fetched {@code develop} last week still
 * resolves {@code refs/remotes/origin/develop}, so accepting the ref after a failed fetch would
 * report last week's commit as this claim's base — exactly the staleness this change exists to end
 * (FR6, M1). And a fetch that exits zero without leaving the destination behind delivered nothing,
 * so the ref must be read back too rather than trusting the status.
 *
 * <p>A fetch that exits and delivers nothing is put to the same probe {@link CommitBaseFetch} uses:
 * by the time this method runs, an earlier refs read already told the caller origin holds the ref,
 * so a non-delivering fetch means either origin stopped answering in between, or it answered the
 * fetch itself but declined — most often a mid-fetch authentication or permission refusal. Git's own
 * words are the wrong evidence to tell those apart (localized, reworded between releases), so
 * {@link OriginProbe} asks a second, simpler question instead: a remote that still answers may put a
 * task-level refusal on the record, one that does not may not.
 *
 * <p>Implements FR6, FR9 of add-base-ref-resolution.
 */
final class RefreshedTip {

    private RefreshedTip() {}

    /**
     * Reads one completed fetch and its destination ref into a refresh outcome.
     *
     * @param runner the git subprocess seam; never null
     * @param cloneDir the factory clone; never null
     * @param fetch the completed fetch whose delivery is in question
     * @param ref the destination ref the fetch named
     * @param name the base ref as the decision named it, for the outcome and the report
     * @param kind which namespace the base lives in
     * @return the refreshed commit, a task-level refusal, or the infrastructure arm naming what the
     *     fetch did
     */
    static BaseRefreshOutcome of(
            GitProcessRunner runner, Path cloneDir, GitCommandResult fetch, String ref, String name, BaseRefKind kind) {
        if (fetch.termination() != Termination.EXITED || fetch.exitCode() != 0) {
            return undelivered(runner, cloneDir, fetch, name, kind);
        }
        return VerifiedTip.read(runner.run(cloneDir, "rev-parse", "--verify", "--quiet", ref + "^{commit}"))
                .<BaseRefreshOutcome>map(commit -> new BaseRefreshOutcome.Refreshed(name, commit, kind))
                .orElseGet(() -> new BaseRefreshOutcome.Unavailable(
                        "the " + label(kind) + " fetch of " + name + " reported success but left no " + ref));
    }

    /**
     * Which failure class a fetch that delivered no object belongs to, once the ref itself is known
     * to have failed to arrive — see {@link OriginProbe}'s own doc for why the probe, not git's exit
     * detail, is the evidence.
     */
    private static BaseRefreshOutcome undelivered(
            GitProcessRunner runner, Path cloneDir, GitCommandResult fetch, String name, BaseRefKind kind) {
        if (fetch.termination() != Termination.EXITED || !new OriginProbe(runner).answers(cloneDir)) {
            return new BaseRefreshOutcome.Unavailable(fetch.failureDetail(label(kind) + " fetch of " + name));
        }
        return new BaseRefreshOutcome.Refused("The base " + label(kind) + " '" + name + "' was found on origin, "
                + "which is reachable, but the fetch was refused: "
                + fetch.failureDetail(label(kind) + " fetch of " + name)
                + ". This is most often an authentication or permission problem for this ref; check credentials "
                + "and access on origin.");
    }

    private static String label(BaseRefKind kind) {
        return kind == BaseRefKind.TAG ? "tag" : "branch";
    }
}
