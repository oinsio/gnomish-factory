package com.github.oinsio.gnomish.domain.engine.port;

import com.github.oinsio.gnomish.domain.engine.PollStatus;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;

/**
 * The port through which the engine performs one poll of an asynchronous
 * third-party verification (e.g. a CI check on the task branch, a SonarQube
 * quality gate) without knowing how the third party is queried (design D2): the
 * engine hands over a {@link VerifyCheck.External} and the opaque {@link
 * Workspace} it relates to, and reads back a single {@link PollStatus}.
 *
 * <p>This port performs a <em>single</em> poll only — the engine owns the poll
 * loop (interval, timeout, and the {@link PollStatus.Running}-until-deadline
 * decision live in the engine, task 4.3), calling {@link #poll} repeatedly until
 * it collapses the observations into one verdict. Submitting the external work is
 * <em>not</em> done here: the factory has no inbound HTTP and relies on the branch
 * push trigger to start third-party checks, so this port only observes an
 * already-running check (NG8).
 *
 * <p>Implements FR3, D2 of add-stage-engine.
 */
public interface ExternalCheckClient {

    /**
     * Polls the external check named by {@code check} once and returns what it
     * currently sees — {@link PollStatus.Pass} or {@link PollStatus.Fail} when the
     * third party has reached a verdict, {@link PollStatus.Running} when it has
     * not yet (the engine polls again after the check's interval), or {@link
     * PollStatus.CannotVerify} when no result could be obtained (network error,
     * unknown check id). The engine never inspects the workspace; it belongs to
     * the adapter.
     *
     * <p>Implements FR3, D2 of add-stage-engine.
     *
     * @param check the external check to poll
     * @param workspace the opaque working copy the check relates to
     * @return the status seen by this single poll; never null
     */
    PollStatus poll(VerifyCheck.External check, Workspace workspace);
}
