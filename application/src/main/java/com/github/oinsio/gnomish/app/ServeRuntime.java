package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.serve.DaemonLoops;
import com.github.oinsio.gnomish.app.serve.FeedAutomaton;
import com.github.oinsio.gnomish.app.serve.ServeShutdown;
import com.github.oinsio.gnomish.app.serve.TakeSlotRunner;
import java.util.Optional;

/**
 * The assembled {@code serve} daemon collaborators {@link ServeRuntimeAssembly#assemble} builds once
 * the tracker is live, handed back to {@link ServeCommand#run} so it only starts them and picks the
 * drain-or-forever branch (process-invariants.md); the ledger writer is already attached to {@code
 * slotRunner} inside {@link ServeRuntimeAssembly#assemble}.
 *
 * <p>Implements FR2, FR13 of add-factory-serve. Implements FR1, FR4, FR9, FR12 of
 * add-serve-observability. Implements D9 of supervise-daemon-loops-and-embed-dashboard: the
 * reaper, the janitor and the sweep tick travel as one {@link DaemonLoops}. Implements FR9, D12 of
 * supervise-daemon-loops-and-embed-dashboard: {@code dashboard} is the embedded page's watch, not
 * yet started, or empty when the effective switch is off — an {@link Optional} rather than a null
 * object, because the command and the shutdown act on it only when it exists (it prints a line,
 * and the final render belongs to a page that is there), and {@link DashboardWatch} is the one
 * concrete assembly D10 allows, with no interface to stand a do-nothing twin behind.
 */
record ServeRuntime(
        FeedAutomaton automaton,
        TakeSlotRunner slotRunner,
        ServeShutdown shutdown,
        DaemonLoops daemonLoops,
        ObservabilityWiring observability,
        Optional<DashboardWatch> dashboard) {}
