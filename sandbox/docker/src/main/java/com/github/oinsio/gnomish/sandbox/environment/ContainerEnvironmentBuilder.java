package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The per-role assembly behind {@link ContainerEnvironments}'s {@code
 * roundEnvironment}/{@code judgeEnvironment}/{@code verificationEnvironment} (FR3, FR8, FR13 of
 * add-sandbox-core): wires a {@link ContainerTaskExecutionEnvironment} with its {@link
 * EgressGuard} and mandatory {@link EnvironmentSelfCheck} into a {@link SelfCheckedEnvironment}
 * for one role key.
 *
 * <p>An instance constructed once with the equipment every role shares (design D11 of
 * add-parameter-count-gate — the fields-not-parameters shape of {@code process-invariants.md});
 * {@link #build} takes only the per-call job, the role key. It is a relay for the timing and the
 * git link: each is taken whole and its members handed on to the leaves that use them. It is not
 * an assembly object in the sense of {@code testing.md}: {@link #scrubsCredential} computes the
 * allowlist probe {@link ContainerEnvironments} delegates to, so the class stays in the mutation
 * scope, its mutants killed by {@code ContainerEnvironmentsSeamSpec}.
 *
 * <p>Implements FR6 of add-parameter-count-gate.
 */
final class ContainerEnvironmentBuilder {

    private final DockerCli docker;
    private final BoxGitLink link;
    private final SandboxProperties sandbox;
    private final BoxTiming timing;
    private final ChildEnvAllowlist allowlist;
    private final Path guardConfigRoot;
    private final ObjectOwnership ownership;

    /**
     * @param docker the docker subprocess seam shared by every role; never null
     * @param link the factory clone the boxes are seeded from and the fetch back into it; never null
     * @param sandbox the operator sandbox config: image, runtime, limits, allowlist; never null
     * @param timing the clock, the self-check pause and the docker command bound; never null
     * @param allowlist the run's layered child-env allowlist (D6, FR9 of add-sandbox-core); never
     *     null
     * @param guardConfigRoot the factory-private directory guard configs render under (per
     *     environment key), never inside a working copy or scratch area; never null
     * @param ownership the mode and project identity stamped on every object this task creates
     *     (FR2, FR8 of add-serve-sandbox-lifecycle); never null
     */
    ContainerEnvironmentBuilder(
            DockerCli docker,
            BoxGitLink link,
            SandboxProperties sandbox,
            BoxTiming timing,
            ChildEnvAllowlist allowlist,
            Path guardConfigRoot,
            ObjectOwnership ownership) {
        this.docker = docker;
        this.link = link;
        this.sandbox = sandbox;
        this.timing = timing;
        this.allowlist = allowlist;
        this.guardConfigRoot = guardConfigRoot;
        this.ownership = ownership;
    }

    /** Builds the self-checked environment for the given role key ({@code <key>}, {@code <key>-j}, or {@code <key>-v}). */
    SelfCheckedEnvironment build(String key) {
        var settings = new TaskContainerSettings(
                sandbox.image(), sandbox.runtime(), sandbox.limits(), sandbox.enforceDiskQuota());
        var environment = new ContainerTaskExecutionEnvironment(
                docker, key, link, settings, timing.clock(), allowlist, ownership);
        var guard = new EgressGuard(
                docker, key, sandbox.guardImage(), sandbox.egressAllowlist(), guardConfigRoot.resolve(key), ownership);
        var selfCheck = new EnvironmentSelfCheck(
                environment, guard, docker, key, sandbox.runtime(), sandbox.egressAllowlist(), timing.sleeper());
        return new SelfCheckedEnvironment(environment, selfCheck, guard);
    }

    /** The ownership mode every object built here is labelled with; see {@link ContainerEnvironments#ownershipMode}. */
    OwnershipMode ownershipMode() {
        return ownership.mode();
    }

    /** Whether the composed allowlist scrubs {@code credentialEnvVar}; see {@link ContainerEnvironments#scrubsCredential}. */
    boolean scrubsCredential(String credentialEnvVar) {
        return allowlist.compose(List.of(), Map.of(credentialEnvVar, "probe")).isEmpty();
    }
}
