package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;

/**
 * The pre-cleanup state {@link DeliveredBranchReader} recovers from a delivered task branch's
 * history: the delivered {@link TaskContext} and the final {@link TaskState} that the deferred
 * finish is rendered from during reconcile-on-resume (FR10, D10 of add-claim-heartbeat).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR10 of add-claim-heartbeat.
 *
 * @param context the delivered task's identity, description and decisions; never null
 * @param finalState the final task state recorded at delivery; never null
 */
public record DeliveredBranchState(TaskContext context, TaskState finalState) {}
