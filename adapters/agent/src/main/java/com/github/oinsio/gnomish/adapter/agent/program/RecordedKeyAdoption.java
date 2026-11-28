package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.executorcontract.ExecutorResult;
import com.github.oinsio.gnomish.pendingkey.PendingKey;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The guard of an adopted key (design D14 of migrate-verify-to-executor-contract, as amended
 * 2026-11-13; wire-contract "A recorded key is adopted only over an unchanged working copy"): the view
 * taken of the resuming instance's working copy becomes the run's before-view only when its {@code
 * HEAD} is the commit the factory put the working copy on — the binding's expected {@code HEAD}, else
 * the round's last registered commit — and its only other entry is the factory's own {@code
 * state.json} lag, judged by identity through {@link FactoryMove}, so a working copy that already holds
 * a killed run's writes is never the view an answer is judged against. Any other view, a round with no
 * registered commit, or a view that could not be taken makes the entry a refusal that the key's poll
 * returns with no process spawned: {@code failed/infrastructure} naming the key and {@code HEAD}, the
 * changed paths, or why the view could not be taken.
 *
 * <p>Implements FR3, FR11 of migrate-verify-to-executor-contract; NFR-S4 of define-executor-contract.
 */
final class RecordedKeyAdoption {

    private RecordedKeyAdoption() {}

    /**
     * The pending run an adopted key names.
     *
     * @param executor the executor's name; never blank
     * @param key the recorded key; never null
     * @param view git's view of the working copy, taken at adoption; never null
     * @param expected the commit the factory put the working copy on; empty when the round has none
     * @param chain the round's registered chain, last registered first; never null
     * @param stateBlobAt the {@code state.json} object a commit carries, read by commit id
     * @return the run, judged against {@code view}, or a refusal; never null
     */
    static PendingRuns.Entry entry(
            String executor,
            PendingKey key,
            WorkingCopyView view,
            Optional<String> expected,
            List<String> chain,
            Function<String, Optional<String>> stateBlobAt) {
        String refused = "the pending key '"
                + UntrustedText.subprocess(key.value()).excerpt(256) + "' recorded for this check cannot be adopted: ";
        return switch (view) {
            case WorkingCopyView.Unreadable unreadable ->
                refusal(executor, refused + "the working copy's state could not be taken: " + unreadable.why());
            case WorkingCopyView.Taken taken -> {
                if (expected.isEmpty()) {
                    yield refusal(executor, refused + "the round has no registered commit the working copy stands on");
                }
                Optional<String> why = FactoryMove.unadoptable(taken.entries(), expected.get(), chain, stateBlobAt);
                yield why.isEmpty() ? new PendingRuns.Entry(taken, null) : refusal(executor, refused + why.get());
            }
        };
    }

    private static PendingRuns.Entry refusal(String executor, String cause) {
        ExecutorResult refusal = ProgramFailures.infrastructure(
                ProgramFailures.Subject.PROGRAM, executor, cause, UntrustedText.factory(""));
        return new PendingRuns.Entry(null, refusal);
    }
}
