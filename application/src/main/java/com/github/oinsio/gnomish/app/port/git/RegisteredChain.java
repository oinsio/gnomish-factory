package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.app.port.agent.RoundClose;
import com.github.oinsio.gnomish.domain.engine.RoundToken;
import java.util.List;
import java.util.stream.Stream;

/**
 * A round's registered chain, held by its {@link CurrentRound} cell: the snapshot commit that closed
 * the round, how it closed, and the round's registered commits — the last registered commit first,
 * each first parent after it, the snapshot last (design D21, D14 as amended 2026-11-13, of
 * migrate-verify-to-executor-contract). The three are always together: a chain starts at its
 * snapshot, and a pending commit only lands on a chain. Immutable; the cell publishes a whole one.
 *
 * <p>Implements FR3, FR20 of migrate-verify-to-executor-contract.
 *
 * @param attemptCommit the snapshot commit the round's checks judge
 * @param close how the round closed, as the snapshot's subject names it
 * @param commits the registered commits, the last registered one first, the snapshot last
 */
record RegisteredChain(String attemptCommit, RoundClose close, List<String> commits) {

    /** The chain a snapshot starts: the snapshot is the round's only registered commit. */
    static RegisteredChain startedBy(String attemptCommit, RoundClose close) {
        return new RegisteredChain(attemptCommit, close, List.of(attemptCommit));
    }

    /**
     * This chain with {@code commit} landed on its last registered commit: {@code commit} becomes the
     * last registered one, the parent of the next pending commit and of the round's close. The
     * snapshot the checks judge is unchanged.
     */
    RegisteredChain landed(String commit) {
        return new RegisteredChain(
                attemptCommit,
                close,
                Stream.concat(Stream.of(commit), commits.stream()).toList());
    }

    /** The round's last registered commit: the snapshot, or the last pending commit landed on it. */
    String lastRegistered() {
        return commits.getFirst();
    }

    /** The round {@code token} names, closed by this chain's snapshot. */
    ClosedRound closedBy(RoundToken token) {
        return new ClosedRound(token, attemptCommit, lastRegistered(), close);
    }
}
