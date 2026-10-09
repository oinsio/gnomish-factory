package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.check.CheckProviderSeam;
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.OriginRemote;
import com.github.oinsio.gnomish.app.git.ProjectIdentity;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.app.serve.SandboxLifecyclePass;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.Segment;
import com.github.oinsio.gnomish.sandbox.environment.BoxGitLink;
import com.github.oinsio.gnomish.sandbox.environment.BoxTiming;
import com.github.oinsio.gnomish.sandbox.environment.ContainerEnvironmentFactory;
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/**
 * Builds a run's {@link ContainerRunSupport} — the production {@link ContainerSupportFactory} the
 * container runners call per run. An instance over the fixed part of the construction (design D7,
 * D10 of collapse-composition-roots; D12 of make-checkpoint-gate-durable): the check credentials
 * the configured providers declared, the check-client registry the pipeline's own check
 * declarations are read through, the ownership label, the tenure record, the two property sets,
 * the installation's {@link ContainerEnvironmentFactory} and the sandbox lifecycle pass — each
 * fixed for the process. What varies per run — the clone, the task, the segment plan, the pipeline
 * and the tracker credentials — is the seam's own five parameters, so a runner has no property set
 * to pass.
 *
 * <p>{@link ContainerSupports} builds one per ownership mode: {@code MANUAL} for {@code gnomish
 * run}, {@code TRACKED} for {@code take}/{@code serve}; the environment factory and the lifecycle
 * pass are therefore built once per mode and shared by every run of it.
 *
 * <p>Implements FR20 of make-checkpoint-gate-durable.
 */
// Null-marked explicitly (JSpecify): this module carries no package-info, and the application
// module's one does not reach this source root, so without the class-level marker the
// ContainerSupportFactory overrides here read as unannotated against their null-marked supertype.
@NullMarked
record ContainerRunSupportFactory(
        List<String> checkCredentialEnvVars,
        Map<String, CheckClientFactory> checkClientRegistry,
        OwnershipMode ownershipMode,
        ClaimEpochSource epochs,
        SandboxProperties sandboxProperties,
        FactoryProperties factoryProperties,
        ContainerEnvironmentFactory environments,
        SandboxLifecyclePass sandboxLifecyclePass)
        implements ContainerSupportFactory {

    /**
     * @param checkCredentialEnvVars the credential names the configured check providers declared
     *     through the SPI (FR17, design D11 of add-plugin-architecture), resolved once by the
     *     composition root — no vendor constant is named here
     * @param checkClientRegistry the discovered check providers, asked what the pipeline's own
     *     check declarations name (FR11 of add-plugin-architecture)
     * @param ownershipMode the ownership label to stamp on every object a run creates (FR2 of
     *     add-serve-sandbox-lifecycle) — this factory never decides it
     * @param epochs the tenure a run's commits are stamped with (FR13 of
     *     harden-task-branch-contract) — always the bundle's own tenure record (FR4 of
     *     fix-claim-epoch-fence), which on the plain {@code gnomish run} path is never written to
     * @param sandboxProperties the operator sandbox config: the env passthrough and the configured
     *     project identity each run reads
     * @param factoryProperties the installation config, read per run only for the git network
     *     deadline the run's git subprocesses are bounded by (FR5, design D8 of
     *     bound-subprocess-commands)
     * @param environments the installation's box equipment, built with {@code ownershipMode}
     *     (design D12 of make-checkpoint-gate-durable)
     * @param sandboxLifecyclePass the runner-start orphan sweep every run of this mode shares
     */
    ContainerRunSupportFactory {
        checkCredentialEnvVars = List.copyOf(checkCredentialEnvVars);
    }

    /**
     * The installation constructor (design D12 of make-checkpoint-gate-durable): builds the
     * environment factory and the sandbox lifecycle pass from the two property sets, once, for every
     * run of this ownership mode, on the system time source.
     */
    ContainerRunSupportFactory(
            List<String> checkCredentialEnvVars,
            Map<String, CheckClientFactory> checkClientRegistry,
            OwnershipMode ownershipMode,
            ClaimEpochSource epochs,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties) {
        // FR18 of supervise-daemon-loops-and-embed-dashboard: one source for the box timing and the
        // sweep pass. Still built here rather than taken from the root's instantSource bean: the
        // ContainerSupports test constructor that builds this factory is at the parameter limit
        // (task 3.3 — open decision).
        this(
                checkCredentialEnvVars,
                checkClientRegistry,
                ownershipMode,
                epochs,
                sandboxProperties,
                factoryProperties,
                InstantSource.system());
    }

    /**
     * Builds the environment factory — {@code instantSource}, the thread sleeper and {@code
     * factory.docker-command-timeout} as the box timing, the factory-private guard config root
     * under {@code java.io.tmpdir}, the ownership label — and the sandbox lifecycle pass on the same
     * {@code instantSource}, so the two measure on one time source (FR18 of
     * supervise-daemon-loops-and-embed-dashboard).
     */
    private ContainerRunSupportFactory(
            List<String> checkCredentialEnvVars,
            Map<String, CheckClientFactory> checkClientRegistry,
            OwnershipMode ownershipMode,
            ClaimEpochSource epochs,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties,
            InstantSource instantSource) {
        this(
                checkCredentialEnvVars,
                checkClientRegistry,
                ownershipMode,
                epochs,
                sandboxProperties,
                factoryProperties,
                new ContainerEnvironmentFactory(
                        sandboxProperties,
                        new BoxTiming(instantSource, new ThreadSleeper(), factoryProperties.dockerCommandTimeout()),
                        Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir")), "gnomish-guard"),
                        ownershipMode),
                SandboxLifecyclePassFactory.create(sandboxProperties, factoryProperties, instantSource));
    }

    /**
     * Builds the run's container support. The child-env allowlist mirrors {@link
     * RunAssembly#assemble}'s own composition — operator passthrough plus the declared credential
     * names: the tracker's, the configured check providers', and those the pipeline's own checks
     * name (FR11, FR17, design D11 of add-plugin-architecture) — because the environments compose
     * exec children before the assembly exists.
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
        credentials.addAll(checkCredentialEnvVars);
        credentials.addAll(CheckProviderSeam.checkCredentialEnvVars(definition, checkClientRegistry));
        var allowlist = ChildEnvAllowlist.of(sandboxProperties.envPassthrough(), credentials);
        // The stamped identity alone, never the sweep's wider scope: the write side stays
        // single-valued, so no object this run creates carries a legacy project label (FR3 of
        // normalize-project-identity-url).
        String projectId = ProjectIdentity.resolve(
                sandboxProperties.projectId(), new OriginRemote(runner).url(cloneDir), cloneDir);
        // The denial restoration is read from the branch tip each time a round box is built
        // (FR17, design D11 of make-checkpoint-gate-durable) — never offered by a later step.
        var taskEnvironments = environments.forTask(
                TaskIdSanitizer.sanitize(taskId),
                new BoxGitLink(cloneDir, new ContainerHarvestFetch(runner, cloneDir)),
                allowlist,
                projectId,
                ContainerTipReader.restorations(runner, cloneDir, TaskIdSanitizer.branchName(taskId)));
        return new ContainerRunSupport(
                runner, cloneDir, taskId, taskEnvironments, segments, sandboxLifecyclePass, epochs);
    }
}
