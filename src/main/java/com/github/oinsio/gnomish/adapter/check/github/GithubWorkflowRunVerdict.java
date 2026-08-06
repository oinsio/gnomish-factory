package com.github.oinsio.gnomish.adapter.check.github;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.domain.engine.PollStatus;
import java.util.List;
import java.util.Optional;

/**
 * Maps the run {@link GithubWorkflowRunQuery#latestMatchingRun} returns to a
 * {@link PollStatus}, per the verdict rule of design D1: platform conclusion
 * {@code success} is the only Pass; every other or unrecognized conclusion
 * fails closed; no matching run, or a run without a conclusion yet, reads as
 * still running. Purely a mapping: it consumes only the already-fetched
 * {@link GithubWorkflowRun}, never queries the platform itself, and never
 * consults {@code status} beyond the presence of {@code conclusion} — task
 * 4.1 later populates {@link PollStatus.Fail}'s findings with failed
 * jobs/steps and log tails; here they are empty.
 *
 * <p>Implements FR2 of add-external-check-github-actions.
 */
final class GithubWorkflowRunVerdict {

    private static final String SUCCESS_CONCLUSION = "success";

    private GithubWorkflowRunVerdict() {}

    /**
     * Returns {@link PollStatus.Pass} on conclusion {@code success}, {@link
     * PollStatus.Fail} on any other non-null conclusion (fail-closed), or
     * {@link PollStatus.Running} when no run matches or the matching run has
     * no conclusion yet.
     *
     * @param matchingRun the run selected by {@link
     *     GithubWorkflowRunQuery#latestMatchingRun}, or empty when no run
     *     matches yet
     * @return the mapped poll status; never null
     */
    static PollStatus fromMatchingRun(Optional<GithubWorkflowRun> matchingRun) {
        return matchingRun.map(GithubWorkflowRunVerdict::fromConclusion).orElseGet(PollStatus.Running::new);
    }

    // PIT documented exception (mirrors TakeBatchExitCode#isNewSmallestNonZero's equivalent-mutant
    // rationale, build.gradle has the style): @DoNotMutate — the null-conclusion branch's return
    // value is provably unobservable through this method's only caller. fromMatchingRun feeds this
    // result through Optional#map, whose contract turns a null-returning mapper into an empty
    // Optional (Optional.ofNullable semantics), which orElseGet then replaces with a freshly
    // constructed PollStatus.Running — the exact same value this branch itself would have returned.
    // A NullReturnValsMutator here is a true equivalent mutant: no covering test, however written
    // against fromMatchingRun (the only accessible entry point — fromConclusion is private), can
    // observe a difference, since both the real and mutated paths end up producing an
    // equals()-equal PollStatus.Running instance. GithubWorkflowRunVerdictSpec's "a run without a
    // conclusion yet reads as Running" and "no matching run reads as Running" scenarios already
    // cover both ways of reaching a Running verdict; nothing about this branch's own logic is
    // otherwise unverified.
    @DoNotMutate
    private static PollStatus fromConclusion(GithubWorkflowRun run) {
        String conclusion = run.conclusion();
        if (conclusion == null) {
            return new PollStatus.Running();
        }
        if (SUCCESS_CONCLUSION.equals(conclusion)) {
            return new PollStatus.Pass();
        }
        return new PollStatus.Fail(List.of());
    }
}
