package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.check.CheckProviderSeam;
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.OriginRemote;
import com.github.oinsio.gnomish.app.git.ProjectIdentity;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.Segment;
import com.github.oinsio.gnomish.sandbox.environment.ContainerEnvironments;
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/**
 * Builds a run's {@link ContainerRunSupport} — the production {@link ContainerSupportFactory} the
 * container runners call per run. An instance over the fixed part of the construction (design D7,
 * D10 of collapse-composition-roots): the check credentials the configured providers declared, the
 * check-client registry the pipeline's own check declarations are read through, the ownership
 * label and the tenure record, each fixed for the process. What varies per run — the clone, the
 * task, the segment plan, the pipeline and the tracker credentials — is the seam's own
 * parameters, and so are the two property sets the seam already carries per call.
 *
 * <p>{@link ContainerSupports} builds one per ownership mode: {@code MANUAL} for {@code gnomish
 * run}, {@code TRACKED} for {@code take}/{@code serve}.
 */
// Null-marked explicitly (JSpecify): this module carries no package-info, and the application
// module's one does not reach this source root, so without the class-level marker the
// ContainerSupportFactory overrides here read as unannotated against their null-marked supertype.
@NullMarked
final class ContainerRunSupportFactory implements ContainerSupportFactory {

    private final List<String> checkCredentialEnvVars;
    private final Map<String, CheckClientFactory> checkClientRegistry;
    private final OwnershipMode ownershipMode;
    private final ClaimEpochSource epochs;

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
     */
    ContainerRunSupportFactory(
            List<String> checkCredentialEnvVars,
            Map<String, CheckClientFactory> checkClientRegistry,
            OwnershipMode ownershipMode,
            ClaimEpochSource epochs) {
        this.checkCredentialEnvVars = List.copyOf(checkCredentialEnvVars);
        this.checkClientRegistry = checkClientRegistry;
        this.ownershipMode = ownershipMode;
        this.epochs = epochs;
    }

    /**
     * Builds the run's container support. The child-env allowlist mirrors {@link
     * RunAssembly#assemble}'s own composition — operator passthrough plus the declared credential
     * names: the tracker's, the configured check providers', and those the pipeline's own checks
     * name (FR11, FR17, design D11 of add-plugin-architecture) — because the environments compose
     * exec children before the assembly exists. {@code factoryProperties} is read only for the two
     * subprocess deadlines the bundle is bounded by (FR5, design D8 of bound-subprocess-commands).
     */
    @Override
    public ContainerRunSupport create(
            Path cloneDir,
            String taskId,
            List<Segment> segments,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties,
            PipelineDefinition definition,
            List<String> credentialEnvVarsToScrub) {
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
        var environments = ContainerEnvironments.forTask(
                TaskIdSanitizer.sanitize(taskId),
                cloneDir,
                new ContainerHarvestFetch(runner, cloneDir),
                sandboxProperties,
                new SystemClock(),
                allowlist,
                new ThreadSleeper(),
                Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir")), "gnomish-guard"),
                ownershipMode,
                projectId,
                factoryProperties.dockerCommandTimeout());
        var sandboxLifecyclePass =
                SandboxLifecyclePassFactory.create(sandboxProperties, factoryProperties, Clock.systemUTC());
        return new ContainerRunSupport(runner, cloneDir, taskId, environments, segments, sandboxLifecyclePass, epochs);
    }
}
