package com.github.oinsio.gnomish.domain.engine.fake

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.UsageFold
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import java.time.Instant

/**
 * Builds a {@link UsageSnapshot} for specs whose subject reads a task's {@code totals} but is not
 * the fold itself (task 5.4 of define-executor-contract): the snapshot of one fixture round whose
 * executor reported the given tokens and no cost, built through the one {@link UsageFold} rather
 * than spelled by hand, so a spec never holds a snapshot the fold could not have produced.
 *
 * <p>Test fixture, not production code.
 */
final class Snapshots {

    private Snapshots() {}

    /**
     * @param tokensByModel the executor's token counts keyed by model id; never null
     * @return the snapshot of one round whose {@code transform} entry reported them, unpriced
     */
    static UsageSnapshot executed(Map<String, TokenUsage> tokensByModel) {
        def round = AttemptRecords.of(0, AttemptRecord.Result.PASSED, Instant.EPOCH, [],
        new ExecutorUsage(null, [], Unpriced.of(tokensByModel)), [],
        Stop.none())
        UsageFold.fold(UsageSnapshot.EMPTY, round)
    }
}
