package com.github.oinsio.gnomish.app.port.run;

import com.github.oinsio.gnomish.app.port.agent.JudgeEnvironmentSource;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource;
import com.github.oinsio.gnomish.app.port.git.AttemptCommitRef;
import com.github.oinsio.gnomish.app.port.git.PendingVerification;
import com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import org.jspecify.annotations.Nullable;

/**
 * The sandboxed-run adapter bundle the run assembly swaps in
 * for the host defaults when a run executes in container mode (the integration
 * pass of add-sandbox-core): the executor's round source (leased box, in-branch
 * decision file, snapshot-closed rounds — FR21, FR23), the fresh judge-box
 * source (D9), the check environment source (same-box / fresh-box, FR13), the
 * factory clone's bare-object reader for builtin checks (FR21, D15), the
 * external-check delivery precondition (FR21), and the run's attempt-commit
 * ref with an optionally pending interrupted verification found on resume
 * (FR21, D15).
 *
 * <p>Every member is a port or a value type: the bundle names what a sandboxed run supplies,
 * never which backend supplies it (task 4.4, D12 of split-into-modules).
 *
 * <p>Implements FR4, FR13, FR15, FR21, FR23 of add-sandbox-core.
 *
 * @param executorRounds the executor's round environment source; never null
 * @param judgeEnvironments the judge's fresh-box source; never null
 * @param checkEnvironments the command-check environment source; never null
 * @param attemptReader the factory clone's bare-object reader builtin checks evaluate the
 *     attempt commit through; never null
 * @param attemptDelivery the external-check push-precondition seam; never null
 * @param attemptCommit the run's attempt-commit ref shared by snapshot, persistence, and
 *     workspace; never null
 * @param pendingVerification an interrupted verification found at the branch tip on resume, to
 *     be consumed without an agent re-run (FR21); null when none
 */
public record SandboxRunPieces(
        RoundEnvironmentSource executorRounds,
        JudgeEnvironmentSource judgeEnvironments,
        CheckEnvironmentSource checkEnvironments,
        GitObjects attemptReader,
        AttemptDelivery attemptDelivery,
        AttemptCommitRef attemptCommit,
        @Nullable PendingVerification pendingVerification) {}
