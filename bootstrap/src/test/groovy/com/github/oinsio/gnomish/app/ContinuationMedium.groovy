package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import java.nio.file.Path

/**
 * One branch medium the identity specs of make-checkpoint-gate-durable (task 5.3) run on: a task
 * parked by a real run, then continued by the real {@code run --resume} — so the continuation
 * commit is landed by the arm that production dispatches to ({@code resumePaused} → {@code
 * approveCheckpoint}, {@code resumeEscalated} → {@code appendDecision} / {@code resumeFrom}), not
 * by a direct repository call.
 *
 * <p>Two implementations, the pair the lifecycle writers are declared as: {@link
 * HostContinuationMedium} ({@code GitTaskRepository}, worktree commits) and {@link
 * ContainerContinuationMedium} ({@code GitObjectsTaskRepository}, bare-object commits). Both
 * replicate every write to a bare {@link #origin}, the durable medium the specs read.
 */
interface ContinuationMedium {

    /** The medium's name, as a data-driven feature shows it. */
    String name()

    /** The bare origin both the run and the resume replicate the task branch to. */
    Path origin()

    /**
     * Runs {@code taskId} from its first stage through {@code definition} with the fake agent
     * playing {@code scenario}, until the run parks; the park reaches {@link #origin}.
     */
    void park(String taskId, PipelineDefinition definition, String scenario)

    /**
     * Commits {@link BranchHistory#STALE_REQUEST} on the parked tip and replicates it: a request
     * carried over from an earlier round, so a continuation's removal of {@code decisions/} has
     * something to remove on every medium and every continuation.
     */
    void plantStaleRequest(String taskId)

    /**
     * Resumes {@code taskId} through the medium's {@code run --resume} with {@code decision} ({@code
     * null} for none). Whatever the continued drive ends on afterwards — a new park, an abort, a
     * completion — is not this call's concern: the specs judge the commits it left.
     */
    void resume(String taskId, PipelineDefinition definition, String scenario, String decision)
}
