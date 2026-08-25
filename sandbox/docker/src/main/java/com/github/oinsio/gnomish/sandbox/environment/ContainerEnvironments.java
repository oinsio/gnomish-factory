package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.DenialCursor;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

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
 * <p>Implements FR3, FR8, FR13, D5, D9 of add-sandbox-core; FR2, FR8 of
 * add-serve-sandbox-lifecycle.
 */
public final class ContainerEnvironments {

    private final DockerCli docker;
    private final String baseKey;
    private final Path sourceClone;
    private final ContainerHarvest harvester;
    private final SandboxProperties sandbox;
    private final Clock clock;
    private final ChildEnvAllowlist allowlist;
    private final Sleeper sleeper;
    private final Path guardConfigRoot;
    private final OwnershipMode mode;
    private final String projectId;

    /** The cursor a previous lease committed, offered to every environment built here; see {@link #restoreDenialCursor}. */
    private @Nullable DenialCursor restoredCursor;

    /**
     * The production construction: a fresh docker subprocess seam per task. Exists because
     * {@link DockerCli} is deliberately package-private — app-layer assemblies name only the
     * environment-facing types. See the canonical constructor below for parameter semantics.
     *
     * @param dockerCommandTimeout the hard bound on each {@code docker} management command this
     *     task issues — the installation's {@code factory.docker-command-timeout}, threaded from
     *     the composition root because {@link DockerCli} is not nameable outside this package
     *     (FR5, FR10, design D8 of bound-subprocess-commands); never null
     * @return the per-task environment seam; never null
     */
    public static ContainerEnvironments forTask(
            String baseKey,
            Path sourceClone,
            ContainerHarvest harvester,
            SandboxProperties sandbox,
            Clock clock,
            ChildEnvAllowlist allowlist,
            Sleeper sleeper,
            Path guardConfigRoot,
            OwnershipMode mode,
            String projectId,
            Duration dockerCommandTimeout) {
        return new ContainerEnvironments(
                new DockerCli(dockerCommandTimeout),
                baseKey,
                sourceClone,
                harvester,
                sandbox,
                clock,
                allowlist,
                sleeper,
                guardConfigRoot,
                mode,
                projectId);
    }

    /**
     * @param docker the docker subprocess seam shared by every role; never null
     * @param baseKey the sanitized task identifier keying the round environment; never blank
     * @param sourceClone the factory clone working copies are seeded from (D3); never null
     * @param harvester the factory-side fetch behind {@code harvest()} (FR5); never null
     * @param sandbox the operator sandbox config: image, runtime, limits, allowlist; never null
     * @param clock the exec start-instant source; never null
     * @param allowlist the run's layered child-env allowlist (D6, FR9); never null
     * @param sleeper the guard-readiness pause seam of the self-check; never null
     * @param guardConfigRoot the factory-private directory guard configs render under (per
     *     environment key), never inside a working copy or scratch area; never null
     * @param mode the ownership mode stamped on every object this task creates (FR2 of
     *     add-serve-sandbox-lifecycle); never null
     * @param projectId the project identity stamped on every object this task creates (FR8 of
     *     add-serve-sandbox-lifecycle); never blank
     */
    ContainerEnvironments(
            DockerCli docker,
            String baseKey,
            Path sourceClone,
            ContainerHarvest harvester,
            SandboxProperties sandbox,
            Clock clock,
            ChildEnvAllowlist allowlist,
            Sleeper sleeper,
            Path guardConfigRoot,
            OwnershipMode mode,
            String projectId) {
        this.docker = docker;
        this.baseKey = baseKey;
        this.sourceClone = sourceClone;
        this.harvester = harvester;
        this.sandbox = sandbox;
        this.clock = clock;
        this.allowlist = allowlist;
        this.sleeper = sleeper;
        this.guardConfigRoot = guardConfigRoot;
        this.mode = mode;
        this.projectId = projectId;
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
        return mode;
    }

    /** The round-box environment for this task's key; self-checked on every materialize (FR8). */
    public SelfCheckedEnvironment roundEnvironment() {
        return environment(baseKey);
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
        return allowlist.compose(List.of(), Map.of(credentialEnvVar, "probe")).isEmpty();
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

    /**
     * Hands this run the denial cursor the task's last attempt committed (FR5 of
     * fix-denial-report-attachment), so a resume onto a surviving guard container reports
     * only its own rounds' denials instead of replaying the container's whole log. Offered
     * to every environment built afterwards; a guard whose live container is not the one
     * the cursor names ignores it, which is what a fresh role box always does.
     *
     * @param cursor the committed cursor; never null
     */
    public void restoreDenialCursor(DenialCursor cursor) {
        restoredCursor = cursor;
    }

    private SelfCheckedEnvironment environment(String key) {
        var built = ContainerEnvironmentBuilder.build(
                docker,
                key,
                sourceClone,
                harvester,
                sandbox,
                clock,
                allowlist,
                sleeper,
                guardConfigRoot,
                new ObjectOwnership(mode, projectId));
        if (restoredCursor != null) {
            built.restoreDenialCursor(restoredCursor);
        }
        return built;
    }
}
