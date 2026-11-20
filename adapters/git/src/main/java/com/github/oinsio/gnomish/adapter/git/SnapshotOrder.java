package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.engine.AttemptKey;
import com.github.oinsio.gnomish.domain.engine.RoundToken;

/**
 * One round's snapshot as the {@link RoundSnapshot} step hands it to its medium: the round, the
 * identity it opened under, and the subject the step chose by the round's close.
 *
 * @param key the round's task, stage and number; never null
 * @param token the round's token, from the run's cell; never null
 * @param subject the snapshot's subject; never blank
 */
record SnapshotOrder(AttemptKey key, RoundToken token, String subject) {}
