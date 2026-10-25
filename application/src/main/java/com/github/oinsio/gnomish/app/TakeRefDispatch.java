package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.ServeProperties;
import com.github.oinsio.gnomish.app.take.TakeExitCodeMapper;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.operatorevent.OperatorEvent;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;

/**
 * Routes one {@code take} invocation's refs to batch, bare, or explicit mode and converts the
 * outcome to the process exit code via {@link TakeExitCodeException}, extracted from {@link
 * TakeCommand#run} for file size.
 *
 * <p>A single-shot take that ends on an operator precondition writes its one coded line here,
 * before the exit code 8 (FR14 of add-sandbox-base-image).
 *
 * <p>Implements FR2, FR3 of add-factory-serve; FR14 of add-sandbox-base-image.
 */
final class TakeRefDispatch {

    private TakeRefDispatch() {}

    static void run(
            TakeDispatcher dispatcher,
            TakeArguments takeArguments,
            BoundTracker bound,
            ServeProperties serveProperties,
            Logger log)
            throws IOException, InterruptedException {
        List<String> refs = takeArguments.refs();
        if (refs.size() >= 2) {
            // Batch take (2+ refs), validated by TakeArgumentsParser (FR2, FR3 of
            // add-factory-serve): every ref through the disposition matrix, up to
            // serveProperties.slots() concurrently (FR2: "the N limit applies to batch and
            // serve" — no separate batch flag).
            List<TakeBatchOutcome> outcomes = dispatcher.runBatch(takeArguments, bound, serveProperties.slots());
            // FR3, NFR-O2, UX3: the checklist summary is logged before the aggregate exit code
            // is thrown, so it is visible regardless of how the caller handles the exit code.
            TakeBatchSummary.log(outcomes, log);
            throw new TakeExitCodeException(TakeBatchExitCode.aggregate(outcomes));
        }
        TakeResult result;
        if (refs.isEmpty()) {
            result = dispatcher.runBare(takeArguments, bound);
        } else {
            result = dispatcher.runExplicit(takeArguments, refs.getFirst(), bound);
        }
        if (result instanceof TakeResult.OperatorPreconditionUnmet unmet) {
            // The command the refusal ends writes the operator's one line (design D19 of
            // add-sandbox-base-image): the checks throw without logging, and the dispatch that
            // classified the refusal logged it at DEBUG.
            log.error(
                    OperatorEvent.TAKE_OPERATOR_PRECONDITION_UNMET.head() + "operator precondition {} unmet: {}",
                    unmet.kind(),
                    unmet.reason().forLog());
        }
        throw new TakeExitCodeException(TakeExitCodeMapper.exitCodeFor(result));
    }
}
