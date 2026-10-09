package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.DenialRestoration;
import java.util.function.Supplier;

/**
 * The per-task construction seam for guarded container environments (the
 * sandbox integration pass of add-sandbox-core): one place that assembles a
 * {@link ContainerTaskExecutionEnvironment} with its {@link EgressGuard} and
 * mandatory {@link EnvironmentSelfCheck}, wrapped as a {@link
 * SelfCheckedEnvironment} so a materialized-but-unchecked box is impossible by
 * construction (FR8, D5).
 *
 * <p>One task uses up to three environment roles, each with its own key —
 * {@code <key>} for the round box, {@code <key>-j} for the fresh judge box
 * (D9), {@code <key>-v} for {@code verify-in: fresh-box} checks (FR13) — so a
 * fresh box can coexist with the live round box: Docker object names derive
 * from the key, and each role gets its own network, volume, container, and
 * guard. All carry the task label of the base key's task.
 *
 * <p>Every object every role creates carries the ownership mode and project identity this seam
 * was built with (FR2, FR8 of add-serve-sandbox-lifecycle) — the caller decides them once, at
 * construction, never per creation call.
 *
 * <p>Built by {@link ContainerEnvironmentFactory#forTask}, the one production construction
 * (design D12 of make-checkpoint-gate-durable).
 *
 * <p>Implements FR3, FR8, FR13, D5, D9 of add-sandbox-core; FR2, FR8 of
 * add-serve-sandbox-lifecycle; FR17, FR19 of make-checkpoint-gate-durable; FR18, FR22 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
public final class ContainerEnvironments {

    private final DockerCli docker;
    private final String baseKey;
    private final ContainerEnvironmentBuilder builder;
    private final Supplier<DenialRestoration> restoration;
    private final BoxTiming timing;

    /**
     * @param docker the docker subprocess seam shared by every role; never null
     * @param baseKey the sanitized task identifier keying the round environment; never blank
     * @param builder the per-role assembly holding everything else a role's environment is built
     *     from — this seam can no longer build one itself; never null
     * @param restoration what the task branch tip records about denials already reported, read
     *     afresh each time a round environment is built (design D11 of
     *     make-checkpoint-gate-durable); never null
     * @param timing the installation's box timing every role is built with — the same value the
     *     builder holds — exposed through {@link #timing()} to the run that owns this seam; never
     *     null
     */
    ContainerEnvironments(
            DockerCli docker,
            String baseKey,
            ContainerEnvironmentBuilder builder,
            Supplier<DenialRestoration> restoration,
            BoxTiming timing) {
        this.docker = docker;
        this.baseKey = baseKey;
        this.builder = builder;
        this.restoration = restoration;
        this.timing = timing;
    }

    /**
     * The installation's box timing this task's environments run on (design D22 of
     * supervise-daemon-loops-and-embed-dashboard): the run that owns this seam reads its time
     * equipment here — for its lifecycle store, its first push and its round source — instead of
     * building one of its own, so a run and its boxes measure on one time source. A plain
     * accessor: the timing carries no authority, only the root's time equipment and the docker
     * command bound.
     *
     * @return the box timing; never null
     */
    public BoxTiming timing() {
        return timing;
    }

    /**
     * The ownership mode stamped on every Docker object this seam creates (FR2 of
     * add-serve-sandbox-lifecycle) — {@code TRACKED} for the claim-backed entry points ({@code
     * take}, {@code serve}), {@code MANUAL} for {@code gnomish run}. Exposed so a daemon-free spec
     * can assert which label a composition root's wiring actually carries, without materializing
     * an object to read it back off a live daemon.
     *
     * @return the ownership mode every object of this task is labelled with; never null
     */
    public OwnershipMode ownershipMode() {
        return builder.ownershipMode();
    }

    /**
     * The round-box environment for this task's key; self-checked on every materialize (FR8).
     *
     * <p>Every round environment is born carrying what the branch tip records about denials
     * already reported (FR17, design D11 of make-checkpoint-gate-durable): the guard container
     * outlives the process that created it, so a box reattaching to a surviving one continues the
     * denial delta from the position its last attempt committed instead of replaying the
     * container's whole log onto this round (FR5 of fix-denial-report-attachment), and merges a
     * full re-read against the recorded identities when that position cannot be used (FR7 of
     * fix-denial-attribution-durability). The offer is read as the box is built — first open,
     * segment boundary and resume reattach alike — so there is no later step a caller could run
     * out of order.
     *
     * <p>Offered to the round environment alone. The committed position and identities name the
     * round box's guard container, and only the round box can ever reattach to it; a judge or
     * verification box is a different key with a guard of its own, so the offer could never
     * apply there — while consuming it would cost that box a rejection: an INFO line about a
     * foreign source and, where the branch records denials, a synthetic "denials may be lost"
     * marker in that box's own findings.
     */
    public SelfCheckedEnvironment roundEnvironment() {
        SelfCheckedEnvironment round = environment(baseKey);
        round.restoreDenials(restoration.get());
        return round;
    }

    /** A fresh judge-box environment ({@code <key>-j}, D9), pinned by its caller at the attempt commit. */
    public SelfCheckedEnvironment judgeEnvironment() {
        return environment(baseKey + "-j");
    }

    /** A fresh verification-box environment ({@code <key>-v}, FR13) for {@code verify-in: fresh-box}. */
    public SelfCheckedEnvironment verificationEnvironment() {
        return environment(baseKey + "-v");
    }

    /** The sanitized key of the round environment, for keep/dispose bookkeeping. */
    public String baseKey() {
        return baseKey;
    }

    /**
     * Testing seam (FR9): whether {@code credentialEnvVar} is excluded from this environment's
     * composed child-env allowlist — the observable proof that construction wired a credential
     * name into scrubbing, without a spec reaching into the private {@link ChildEnvAllowlist}
     * construction state to check it.
     */
    public boolean scrubsCredential(String credentialEnvVar) {
        return builder.scrubsCredential(credentialEnvVar);
    }

    /**
     * Keep semantics for an ended task (FR6, git-task-persistence "Worktree
     * lifecycle"): the round container is stopped so no gnome process keeps
     * executing, while volume and network remain for salvage and resume.
     */
    public void stopKeeping() {
        new ContainerEnvironmentKeeper(docker).stopKeeping(baseKey);
    }

    /**
     * Disposes whatever objects exist for this task's round key — including a
     * kept environment left by a previous (possibly dead) factory instance that
     * no live lease holds ({@code --discard-work}, FR6): container, volume, and
     * network go together, so the next materialize seeds a fresh clone from the
     * branch instead of reattaching to the surviving volume.
     */
    public void disposeExisting() {
        new ContainerEnvironmentDisposal(docker).dispose(baseKey);
    }

    private SelfCheckedEnvironment environment(String key) {
        return builder.build(key);
    }
}
