package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.port.pipeline.BoundTaskTier;
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource;
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces;
import com.github.oinsio.gnomish.domain.engine.EnginePorts;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.io.IOException;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Builds one run's collaborators once its {@link TaskContext} and initial {@link TaskState} are
 * known: the outcome loop, the {@link EnginePorts} the engine drives, and the shared status
 * snapshot. Every run path — in-place, git, container, and each {@code take} slot — goes through
 * this one seam, so which executor, judge, check runner and console a run actually gets is settled
 * in a single place.
 *
 * <p>An {@code application}-owned port (FR12b, design D12 of split-into-modules). The realization
 * is a composition class: it names the CLI and console adapters, the check runners, the pipeline
 * law reader and the external-check client factory, which is why it belongs in {@code bootstrap}
 * by D3's by-role rule. Holding it directly is what kept every runner and {@code take} step —
 * genuine use cases — bound to the composition root; the use cases hold this interface instead.
 *
 * <p>Instances are values: {@link #withExtraListener} and {@link #withSandbox} return a modified
 * copy rather than mutating, so the shared assembly a {@code serve} instance reuses across slots is
 * never disturbed by one slot's per-run enrichment.
 *
 * <p>The assembly is also where a task's law is read from the binding it runs under (design D14
 * of add-base-ref-resolution): {@link #assemble} opens the law at the binding to freeze it, and
 * {@link #bindTaskTier} reads the task tier of the definition from the same binding first, through
 * the {@link PipelineSource} the invocation attached with {@link #withPipelineSource} — the very
 * source the startup definition was loaded from, so the two reads share one registry of validators
 * and providers by construction.
 *
 * <p>Implements FR12b of split-into-modules; FR7, FR10, D6, D10 of add-agent-executor; D10 of
 * add-manual-run; FR7 of add-git-workflow; FR1, FR11 of add-claim-heartbeat; FR13 of
 * add-base-ref-resolution.
 */
public interface RunAssembly {

    /**
     * Builds the per-run outcome loop and engine ports for one invocation.
     *
     * @param definition the loaded pipeline the run advances through; never null
     * @param context the task's identity and human decisions; never null
     * @param initialState the state the first engine call resumes from; never null
     * @param interactiveMode which role(s), if any, use the interactive console adapter (FR10, D6)
     * @param attemptPersistence the realization rounds commit through — supplied per call, not
     *     fixed at construction (design D8 of add-git-workflow): in-place mode passes the shared
     *     in-memory store, git mode a fresh worktree-rooted one, container mode the sandboxed one
     * @param credentialEnvVarsToScrub the active tracker adapter's declared credential env-var
     *     names (D17, NFR-S1 of add-tracker-port), combined with the operator's configured
     *     passthrough into the run's child-environment allowlist; empty for a plain {@code run}
     * @param lawBinding which tree the pipeline law is frozen from at invocation start (D14, FR19
     *     of add-sandbox-core; D12 of add-base-ref-resolution) — the law commit wherever a ref was
     *     resolved, the working tree in the in-place mode and a manual {@code run} without
     *     {@code --base}; never the gnome's per-task worktree, so a running task cannot rewrite
     *     its own instructions or acceptance criteria. The binding also names the repository the
     *     external-check pin guard reads its repository-relative pin paths from: in the in-place
     *     mode that root may not be a repository at all, in which case a check that declares pin
     *     paths degrades fail-closed
     * @return the outcome loop and the ports it drives; never null
     */
    Run assemble(
            PipelineDefinition definition,
            TaskContext context,
            TaskState initialState,
            RunArguments.InteractiveMode interactiveMode,
            AttemptPersistence attemptPersistence,
            List<String> credentialEnvVarsToScrub,
            LawBinding lawBinding);

    /**
     * Builds a standalone {@link DialogConsole} for a resume dialog that runs before any {@link
     * #assemble} call (design D9, task 4.7 of add-git-workflow): the resume dialogs need the exact
     * console a live run uses ("same questions, same feel", UX2) without yet having an engine to
     * hang it off of.
     *
     * @param context the resumed task's identity and decisions, for the {@code status} meta-command
     * @param state the resumed task's current state, seeding the status snapshot
     * @return a fresh console wired the way {@link #assemble} wires its own; never null
     */
    DialogConsole dialogConsole(TaskContext context, TaskState state);

    /**
     * Returns a copy of this assembly that also fans every engine event into {@code listener} (task
     * 6.1, FR1 of add-claim-heartbeat): a {@code take} run adds its per-run heartbeat progress
     * without disturbing the shared assembly the manual run reuses.
     *
     * @param listener the additional listener to join the run's composite; never null
     * @return a new assembly identical but for the added listener; never null
     */
    RunAssembly withExtraListener(EngineEventListener listener);

    /**
     * Returns a copy of this assembly whose runs execute in container mode through {@code pieces}:
     * executor rounds in the leased box with snapshot-closed rounds, judge votes in fresh boxes,
     * command checks per their freshness knob, builtin checks against the attempt commit, external
     * checks behind the delivery precondition. Host runs never call this and are untouched (G4,
     * D20 of add-sandbox-core).
     *
     * @param pieces the sandboxed-run bundle; never null
     * @return a new assembly identical but for the sandbox pieces; never null
     */
    RunAssembly withSandbox(SandboxRunPieces pieces);

    /**
     * Returns a copy of this assembly whose host executor rounds are decorated by {@code
     * decoration} (FR1, FR3, design D3 of wire-host-mid-round-push): git-mode host control flows
     * attach the mid-round push decoration this way, in-place mode never calls it, and a run in
     * container mode ignores it — the sandbox rounds win by construction. The decoration is a
     * value, not a flag: the composition root builds the operator, so no git-adapter knowledge
     * enters this layer, and the default is {@code UnaryOperator.identity()} so consumers apply
     * it unconditionally.
     *
     * @param decoration the round-source decoration the composition root built; never null
     * @return a new assembly identical but for the decoration; never null
     */
    RunAssembly withHostGitPush(UnaryOperator<RoundEnvironmentSource> decoration);

    /**
     * Returns a copy of this assembly whose runs read their task tier through {@code
     * pipelineSource} (FR13, design D14 of add-base-ref-resolution): {@code take} and {@code
     * serve} attach the source their startup definition came from, once per invocation, exactly
     * where they attach the heartbeat listener.
     *
     * @param pipelineSource where a task's law is read from by binding; never null
     * @return a new assembly identical but for the attached source; never null
     */
    RunAssembly withPipelineSource(PipelineSource pipelineSource);

    /**
     * Reads the task tier of the law {@code binding} names — the definition that governs one task,
     * or every located problem that keeps it from loading — through the attached {@link
     * PipelineSource}. Only the task tier: the trusted tier was bound at startup and is never
     * re-read on a claim (D15), which this method makes structural rather than promised.
     *
     * @param binding which repository and revision the task's law is read from; never null
     * @return the task tier and its law commit; never null
     * @throws IOException if the law cannot be read at all — an I/O fault, never a validation
     *     problem
     * @throws IllegalStateException if no source was attached — a wiring fault, never a per-task
     *     condition
     */
    BoundTaskTier bindTaskTier(LawBinding binding) throws IOException;
}
