package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.domain.branch.BranchShapeClassifier;
import java.util.Optional;

/**
 * Reads one branch tip down to the point every tip reader needs before it diverges: the tip's
 * {@link BranchShape} and, when that shape carries state (FR16 of harden-task-branch-contract),
 * its raw {@code task.json} / {@code state.json} text. {@link BranchStateReader} and {@link
 * TaskBranchLister} both stop here and build their own result type from what it returns, so the
 * classify-then-read-envelopes sequence has exactly one implementation instead of two hand-kept in
 * step.
 *
 * <p>Implements FR13, FR16 of harden-task-branch-contract.
 */
final class TipEnvelopeReader {

    private static final String TASK_JSON_PATH = GnomishTaskPaths.TASK_JSON_PATH;
    private static final String STATE_JSON_PATH = GnomishTaskPaths.STATE_JSON_PATH;

    private final BranchTipFactsReader facts = new BranchTipFactsReader();
    private final BranchShapeClassifier classifier = new BranchShapeClassifier();

    /**
     * Classifies {@code source}'s tip and reads its envelopes when the shape carries state.
     *
     * @param source the medium to read the tip through
     * @return {@link TipEnvelopeRead.NoState} for a shape with nothing to render, or {@link
     *     TipEnvelopeRead.Loaded} with both envelope texts
     */
    TipEnvelopeRead read(BranchTipSource source) {
        BranchShape shape = classifier.classify(facts.read(source, null));
        if (!shape.tipCarriesState()) {
            return new TipEnvelopeRead.NoState(shape);
        }
        Optional<String> taskJson = source.readAtTip(TASK_JSON_PATH);
        Optional<String> stateJson = source.readAtTip(STATE_JSON_PATH);
        if (taskJson.isEmpty() || stateJson.isEmpty()) {
            // A pre-contract Created tip (FR3): identity without state, or state without identity.
            return new TipEnvelopeRead.NoState(shape);
        }
        return new TipEnvelopeRead.Loaded(shape, taskJson.get(), stateJson.get());
    }
}
