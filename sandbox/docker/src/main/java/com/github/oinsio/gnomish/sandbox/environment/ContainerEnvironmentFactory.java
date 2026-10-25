package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.DenialRestoration;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * The one place the installation's box equipment meets a task's inputs (design D12 of
 * make-checkpoint-gate-durable): a facade with one return type (ADR 0010), constructed with what
 * the installation decides about every box before any task exists — the operator sandbox config,
 * the timing every box operation runs on, the guard config root and the ownership mode — whose
 * one method takes what varies per task and builds that task's {@link ContainerEnvironments}.
 *
 * <p>The split is by lifetime, not by count: an installation input has its home in the
 * constructor and a per-task input in {@link #forTask}, so a new input of either kind pressures
 * neither. The constructor gathers the installation half into one {@link BoxEquipment}, built
 * once and handed whole to every task's {@link ContainerEnvironments} (design D12 of
 * tighten-spec-contract), so no constructor down the chain takes that half as loose parameters
 * beside a task's inputs. The installation's {@link DockerCli} is created here from the timing,
 * because it is deliberately package-private — app-layer assemblies name only the
 * environment-facing types.
 *
 * <p>Implements FR17, FR19 of make-checkpoint-gate-durable; FR5, FR10 of
 * bound-subprocess-commands (the docker command bound threaded from the composition root); FR13
 * of tighten-spec-contract.
 */
public final class ContainerEnvironmentFactory {

    private final BoxEquipment equipment;

    /**
     * @param sandbox the operator sandbox config: image, runtime, limits, allowlist; never null
     * @param timing the exec clock, the self-check pause and the docker command bound — the
     *     installation's {@code factory.docker-command-timeout}; never null
     * @param guardConfigRoot the factory-private directory guard configs render under (per
     *     environment key), never inside a working copy or scratch area; never null
     * @param ownershipMode the mode stamped on every object every task built here creates (FR2 of
     *     add-serve-sandbox-lifecycle); never null
     */
    public ContainerEnvironmentFactory(
            SandboxProperties sandbox, BoxTiming timing, Path guardConfigRoot, OwnershipMode ownershipMode) {
        this.equipment = new BoxEquipment(
                new DockerCli(timing.dockerCommandTimeout()),
                sandbox,
                timing,
                guardConfigRoot,
                ownershipMode,
                ImageContractCheck.current());
    }

    /**
     * Builds one task's environment seam over the installation's box equipment.
     *
     * @param baseKey the sanitized task identifier keying the round environment; never blank
     * @param link the factory clone working copies are seeded from and the fetch behind {@code
     *     harvest()} (D3, FR5 of add-sandbox-core); never null
     * @param allowlist the run's layered child-env allowlist (D6, FR9 of add-sandbox-core); never
     *     null
     * @param projectId the project identity stamped beside the ownership mode on every object this
     *     task creates (FR8 of add-serve-sandbox-lifecycle); never blank
     * @param restoration what the task branch tip records about denials already reported, read
     *     each time a round environment is built (design D11 of make-checkpoint-gate-durable);
     *     never null
     * @return the per-task environment seam; never null
     */
    public ContainerEnvironments forTask(
            String baseKey,
            BoxGitLink link,
            ChildEnvAllowlist allowlist,
            String projectId,
            Supplier<DenialRestoration> restoration) {
        return new ContainerEnvironments(equipment, baseKey, link, allowlist, projectId, restoration);
    }
}
