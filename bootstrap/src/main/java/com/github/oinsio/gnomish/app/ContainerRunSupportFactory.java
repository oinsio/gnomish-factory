package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.OriginRemote;
import com.github.oinsio.gnomish.app.describe.ExecutorEgress;
import com.github.oinsio.gnomish.app.git.ProjectIdentity;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.SandboxEgress;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.Segment;
import com.github.oinsio.gnomish.sandbox.environment.BoxGitLink;
import com.github.oinsio.gnomish.sandbox.environment.BoxTiming;
import com.github.oinsio.gnomish.sandbox.environment.ContainerEnvironmentFactory;
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/**
 * Builds a run's {@link ContainerRunSupport} — the production {@link ContainerSupportFactory} the
 * container runners call per run. An instance over the fixed part of the construction (design D7,
 * D10 of collapse-composition-roots; D12 of make-checkpoint-gate-durable): the installation's
 * child-environment allowlist (passthrough, the built-ins' credential names, the secrets port as its
 * value source), the built-ins' subsections the pipeline's own check credentials are read through, the ownership label, the tenure record, the two property sets,
 * the installation's {@link ContainerEnvironmentFactory} and the sandbox lifecycle pass — each
 * fixed for the process. What varies per run — the clone, the task, the segment plan, the pipeline
 * and the tracker credentials — is the seam's own five parameters, so a runner has no property set
 * to pass.
 *
 * <p>{@link ContainerSupports} builds one per ownership mode: {@code MANUAL} for {@code gnomish
 * run}, {@code TRACKED} for {@code take}/{@code serve}; the environment factory and the lifecycle
 * pass are therefore built once per mode and shared by every run of it.
 *
 * <p>Implements FR20 of make-checkpoint-gate-durable; FR18, FR22 of
 * supervise-daemon-loops-and-embed-dashboard; NFR-S3 of tighten-spec-contract.
 *
 * @param childEnv the installation's child-environment allowlist: the operator passthrough,
 *     the SCM built-ins' credential names (FR17, design D11 of add-plugin-architecture) — no
 *     vendor constant is named here — and the
 *     secrets port as the source of every value it admits (NFR-S3 of tighten-spec-contract);
 *     each run widens it by its own credential names
 * @param subsections the operator's built-in check subsections, asked what the pipeline's own
 *     checks name (FR11 of add-plugin-architecture; task 5.2 of migrate-verify-to-executor-contract)
 * @param ownershipMode the ownership label to stamp on every object a run creates (FR2 of
 *     add-serve-sandbox-lifecycle) — this factory never decides it
 * @param epochs the tenure a run's commits are stamped with (FR13 of
 *     harden-task-branch-contract) — always the bundle's own tenure record (FR4 of
 *     fix-claim-epoch-fence), which on the plain {@code gnomish run} path is never written to
 * @param sandboxProperties the operator sandbox config: the configured project identity each
 *     run reads
 * @param factoryProperties the installation config, read per run only for the git network
 *     deadline the run's git subprocesses are bounded by (FR5, design D8 of
 *     bound-subprocess-commands)
 * @param environments the installation's box equipment, built with {@code ownershipMode}
 *     (design D12 of make-checkpoint-gate-durable)
 * @param sandboxLifecyclePass the runner-start orphan sweep every run of this mode shares
 */
// Null-marked explicitly (JSpecify): this module carries no package-info, and the application
// module's one does not reach this source root, so without the class-level marker the
// ContainerSupportFactory overrides here read as unannotated against their null-marked supertype.
@NullMarked
record ContainerRunSupportFactory(
        ChildEnvAllowlist childEnv,
        BuiltInCheckSubsections subsections,
        OwnershipMode ownershipMode,
        ClaimEpochSource epochs,
        SandboxProperties sandboxProperties,
        FactoryProperties factoryProperties,
        ContainerEnvironmentFactory environments,
        SandboxLifecyclePass sandboxLifecyclePass)
        implements ContainerSupportFactory {

    /**
     * The installation constructor (design D12 of make-checkpoint-gate-durable; D22 of
     * supervise-daemon-loops-and-embed-dashboard): builds the environment factory — {@code time}
     * and {@code factory.docker-command-timeout} as the box timing, the factory-private guard
     * config root under {@code java.io.tmpdir}, the ownership label — and the sandbox lifecycle
     * pass on the same equipment's clock, once, for every run of this ownership mode, so the two
     * measure on the root's one time source (FR18, FR22 of
     * supervise-daemon-loops-and-embed-dashboard).
     */
    ContainerRunSupportFactory(
            ChildEnvAllowlist childEnv,
            BuiltInCheckSubsections subsections,
            OwnershipMode ownershipMode,
            ClaimEpochSource epochs,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties,
            TimeEquipment time) {
        this(
                childEnv,
                subsections,
                ownershipMode,
                epochs,
                sandboxProperties,
                factoryProperties,
                new ContainerEnvironmentFactory(
                        sandboxProperties,
                        new SandboxEgress(factoryProperties.egress().sandbox()),
                        new BoxTiming(time, factoryProperties.dockerCommandTimeout()),
                        Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir")), "gnomish-guard"),
                        ownershipMode),
                SandboxLifecyclePassFactory.create(sandboxProperties, factoryProperties, time.clock()));
    }

    /**
     * Builds the run's container support. The child-env allowlist mirrors {@link
     * RunAssembly#assemble}'s own composition — operator passthrough plus the declared credential
     * names: the tracker's and the built-ins' — each SCM built-in's token name and each {@code http}
     * check's own credential (FR11, FR17, design D11 of add-plugin-architecture) — its values from the
     * secrets port —
     * because the environments compose exec children before the assembly exists. A {@code program}
     * executor's credentials are deliberately not declared here: declared names are what every child
     * is denied, and the program's child takes them as its own resolved layer through the allowlist's
     * {@code programGrant} (NFR-S1, design D10 of define-executor-contract).
     */
    @Override
    public ContainerRunSupport create(
            Path cloneDir,
            String taskId,
            List<Segment> segments,
            PipelineDefinition definition,
            List<String> credentialEnvVarsToScrub) {
        // Per run, not a component: the runner holds no state, only the property-bounded deadline.
        var runner = new GitProcessRunner(factoryProperties.gitNetworkTimeout());
        List<String> credentials = new ArrayList<>(credentialEnvVarsToScrub);
        credentials.addAll(subsections.credentialNames(definition));
        var allowlist = childEnv.declaring(credentials);
        // The stamped identity alone, never the sweep's wider scope: the write side stays
        // single-valued, so no object this run creates carries a legacy project label (FR3 of
        // normalize-project-identity-url).
        String projectId = ProjectIdentity.resolve(
                sandboxProperties.projectId(), new OriginRemote(runner).url(cloneDir), cloneDir);
        // The denial restoration is read at the registered commit each time a round box is built
        // (FR17, design D11 of make-checkpoint-gate-durable) — never offered by a later step.
        var taskEnvironments = environments.forTask(
                TaskIdSanitizer.sanitize(taskId),
                new BoxGitLink(cloneDir, new ContainerHarvestFetch(runner, cloneDir)),
                allowlist,
                projectId,
                ContainerTipReader.restorations(runner, cloneDir, taskId),
                // The hosts the law grants its program executors, which describe then confirms
                // before the first round (design D10 of define-executor-contract).
                ExecutorEgress.union(definition));
        return new ContainerRunSupport(
                runner, cloneDir, taskId, taskEnvironments, segments, sandboxLifecyclePass, epochs);
    }
}
