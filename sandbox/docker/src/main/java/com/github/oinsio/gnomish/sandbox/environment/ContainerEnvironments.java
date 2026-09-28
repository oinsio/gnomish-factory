package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.DenialRestoration;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.nio.file.Path;
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
    private final ContainerEnvironmentBuilder builder;

    /** What a previous lease recorded, offered to every environment built here; see {@link #restoreDenials}. */
    private @Nullable DenialRestoration restoredDenials;

    /**
     * The production construction: a fresh docker subprocess seam per task, bounded by {@code
     * timing.dockerCommandTimeout()} — the installation's {@code factory.docker-command-timeout},
     * threaded from the composition root because {@link DockerCli} is not nameable outside this
     * package (FR5, FR10, design D8 of bound-subprocess-commands). Exists because {@link DockerCli}
     * is deliberately package-private — app-layer assemblies name only the environment-facing
     * types (design D11 of add-parameter-count-gate for the parameter shape).
     *
     * @param baseKey the sanitized task identifier keying the round environment; never blank
     * @param link the factory clone working copies are seeded from and the fetch behind {@code
     *     harvest()} (D3, FR5 of add-sandbox-core); never null
     * @param sandbox the operator sandbox config: image, runtime, limits, allowlist; never null
     * @param timing the exec clock, the self-check pause and the docker command bound; never null
     * @param allowlist the run's layered child-env allowlist (D6, FR9); never null
     * @param guardConfigRoot the factory-private directory guard configs render under (per
     *     environment key), never inside a working copy or scratch area; never null
     * @param ownership the mode and project identity stamped on every object this task creates
     *     (FR2, FR8 of add-serve-sandbox-lifecycle); never null
     * @return the per-task environment seam; never null
     */
    public static ContainerEnvironments forTask(
            String baseKey,
            BoxGitLink link,
            SandboxProperties sandbox,
            BoxTiming timing,
            ChildEnvAllowlist allowlist,
            Path guardConfigRoot,
            ObjectOwnership ownership) {
        var docker = new DockerCli(timing.dockerCommandTimeout());
        return new ContainerEnvironments(
                docker,
                baseKey,
                new ContainerEnvironmentBuilder(docker, link, sandbox, timing, allowlist, guardConfigRoot, ownership));
    }

    /**
     * @param docker the docker subprocess seam shared by every role; never null
     * @param baseKey the sanitized task identifier keying the round environment; never blank
     * @param builder the per-role assembly holding everything else a role's environment is built
     *     from — this seam can no longer build one itself; never null
     */
    ContainerEnvironments(DockerCli docker, String baseKey, ContainerEnvironmentBuilder builder) {
        this.docker = docker;
        this.baseKey = baseKey;
        this.builder = builder;
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
     * The one role that carries a resume's restored denials (see {@link #restoreDenials}): its
     * guard is the container the committed position was read from.
     */
    public SelfCheckedEnvironment roundEnvironment() {
        SelfCheckedEnvironment round = environment(baseKey);
        DenialRestoration restoration = restoredDenials;
        if (restoration != null) {
            round.restoreDenials(restoration);
        }
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

    /**
     * Hands this run what the task branch already records about its denials (FR5 of
     * fix-denial-report-attachment; FR7 of fix-denial-attribution-durability): the position
     * committed with them, so a resume onto a surviving guard container reports only its own
     * rounds' denials instead of replaying the container's whole log, and their identities, so
     * a resume that cannot use the position merges its re-read instead of doubling the report.
     *
     * <p>Offered to the round environment alone. The committed position and identities name the
     * round box's guard container, and only the round box can ever reattach to it; a judge or
     * verification box is a different key with a guard of its own, so the offer could never
     * apply there — while consuming it would cost that box a rejection: an INFO line about a
     * foreign source and, where the branch records denials, a synthetic "denials may be lost"
     * marker in that box's own findings. Neither role reads denials today, so the offer was
     * inert rather than wrong; not making it is what keeps it that way.
     *
     * @param restoration what the branch tip records about denials already reported; never null
     */
    public void restoreDenials(DenialRestoration restoration) {
        restoredDenials = restoration;
    }

    private SelfCheckedEnvironment environment(String key) {
        return builder.build(key);
    }
}
