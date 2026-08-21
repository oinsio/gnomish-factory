package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.StandingReaper;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.SandboxLifecycleTick;
import com.github.oinsio.gnomish.app.serve.ServeShutdown;
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner;
import com.github.oinsio.gnomish.app.serve.WorktreeJanitor;

/**
 * The assembled {@code serve} daemon collaborators {@link ServeRuntimeAssembly#assemble} builds once
 * the tracker is live, handed back to {@link ServeCommand#run} so it only starts them and picks the
 * drain-or-forever branch (process-invariants.md); the ledger writer is already attached to {@code
 * slotRunner} inside {@link ServeRuntimeAssembly#assemble}.
 *
 * <p>Implements FR2, FR13 of add-factory-serve. Implements FR1, FR4, FR9, FR12 of
 * add-serve-observability.
 */
record ServeRuntime(
        FeedAutomaton automaton,
        TakeSlotRunner slotRunner,
        ServeShutdown shutdown,
        WorktreeJanitor worktreeJanitor,
        StandingReaper standingReaper,
        ObservabilityWiring observability,
        SandboxLifecycleTick sandboxLifecycleTick) {}
