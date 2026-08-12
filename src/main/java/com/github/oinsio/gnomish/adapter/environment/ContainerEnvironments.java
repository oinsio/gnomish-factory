package com.github.oinsio.gnomish.adapter.environment;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.SandboxProperties;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>Implements FR3, FR8, FR13, D5, D9 of add-sandbox-core.
 */
@SuppressWarnings("ClassCanBeRecord")
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

    /**
     * The production construction: a fresh docker subprocess seam per task. Exists because
     * {@link DockerCli} is deliberately package-private — app-layer assemblies name only the
     * environment-facing types. See the canonical constructor below for parameter semantics.
     *
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
            Path guardConfigRoot) {
        return new ContainerEnvironments(
                new DockerCli(), baseKey, sourceClone, harvester, sandbox, clock, allowlist, sleeper, guardConfigRoot);
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
            Path guardConfigRoot) {
        this.docker = docker;
        this.baseKey = baseKey;
        this.sourceClone = sourceClone;
        this.harvester = harvester;
        this.sandbox = sandbox;
        this.clock = clock;
        this.allowlist = allowlist;
        this.sleeper = sleeper;
        this.guardConfigRoot = guardConfigRoot;
    }

    /**
     * Whether the Docker runtime answers at all — the container-mode
     * prerequisite probe behind the fail-closed D13 refusal ("install Docker or
     * explicitly bind host"), never a silent fallback (G2).
     *
     * <p>PIT M4 documented exception: {@code @DoNotMutate} — this wrapper only
     * binds the probe to the real {@code docker} binary of the machine the test
     * happens to run on (an integration boundary, the same category as {@code
     * forTask}'s production wiring): a unit test cannot deterministically assert
     * its boolean against a daemon it does not control. The whole probe decision
     * — ok-exit true, non-zero false, unreachable-runtime false — lives in the
     * package-private overload below and is fully covered by
     * ContainerEnvironmentsSpec.
     *
     * @return true iff the docker daemon responded
     */
    @DoNotMutate
    public static boolean dockerAvailable() {
        return dockerAvailable(new DockerCli());
    }

    /** The seam-testable probe behind {@link #dockerAvailable()}: true iff {@code docker version} answers ok. */
    static boolean dockerAvailable(DockerCli docker) {
        try {
            return docker.run(List.of("version", "--format", "{{.Server.Version}}"))
                    .ok();
        } catch (DockerUnavailableException e) {
            return false;
        }
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
        new ContainerEnvironmentReaper(docker, new ContainerEnvironmentDisposal(docker)).stopKeeping(baseKey);
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
     * The startup orphan sweep (FR11, NFR-R2): removes every factory-labelled
     * container, volume, and network left by a dead instance, preserving only
     * this task's three role environments — the round box, the fresh judge box
     * ({@code -j}), and the fresh verification box ({@code -v}) — so a resume that
     * reattaches to a kept environment is never swept out from under itself. A
     * missing Docker runtime is not an error: the sweep logs and does nothing,
     * never blocking startup. Mirrors {@code git worktree prune} at runner start.
     */
    public void sweepOrphans() {
        new ContainerOrphanSweeper(docker).sweep(Set.of(baseKey, baseKey + "-j", baseKey + "-v"));
    }

    private SelfCheckedEnvironment environment(String key) {
        return ContainerEnvironmentBuilder.build(
                docker, key, sourceClone, harvester, sandbox, clock, allowlist, sleeper, guardConfigRoot);
    }
}
