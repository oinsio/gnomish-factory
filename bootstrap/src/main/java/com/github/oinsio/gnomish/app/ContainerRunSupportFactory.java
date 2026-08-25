package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.OriginRemote;
import com.github.oinsio.gnomish.app.git.ProjectIdentity;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.Segment;
import com.github.oinsio.gnomish.sandbox.environment.ContainerEnvironments;
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds a {@link ContainerRunSupport} from scratch (production entry point, as opposed to the
 * daemon-free test constructor). Extracted from {@link ContainerRunSupport} for file size; the
 * behavior is unchanged.
 */
final class ContainerRunSupportFactory {

    private ContainerRunSupportFactory() {}

    /**
     * Builds the run's container support. The child-env allowlist mirrors {@link
     * RunAssembly#assemble}'s own composition — operator passthrough plus the declared
     * credential names (the external-check token added when that adapter is configured, FR26) —
     * because the environments compose exec children before the assembly exists.
     *
     * @param cloneDir the factory clone; never null
     * @param taskId the task whose environments this run owns; never blank
     * @param segments the run's segment plan; never empty
     * @param sandboxProperties the operator sandbox config; never null
     * @param factoryProperties the installation config; read here only for the two subprocess
     *     deadlines this bundle's collaborators are bounded by — {@code factory.git-network-timeout}
     *     for the shared git runner and {@code factory.docker-command-timeout} for the task's
     *     container environments and the lifecycle pass (FR5, design D8 of
     *     bound-subprocess-commands); never null
     * @param checkCredentialEnvVars the credential names the configured check providers declared
     *     through the SPI (FR17, design D11 of add-plugin-architecture), resolved once by the
     *     composition root — no vendor constant is named here
     * @param credentialEnvVarsToScrub the active tracker adapter's declared credential names;
     *     empty for plain {@code gnomish run}
     * @param ownershipMode the ownership label to stamp on every object this run creates (FR2 of
     *     add-serve-sandbox-lifecycle): {@code MANUAL} for {@code gnomish run}, {@code TRACKED}
     *     for {@code take}/{@code serve} — the caller's lambda closes over its own constant, this
     *     factory never decides it
     */
    static ContainerRunSupport create(
            Path cloneDir,
            String taskId,
            List<Segment> segments,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties,
            List<String> checkCredentialEnvVars,
            List<String> credentialEnvVarsToScrub,
            OwnershipMode ownershipMode) {
        var runner = new GitProcessRunner(factoryProperties.gitNetworkTimeout());
        List<String> credentials = new ArrayList<>(credentialEnvVarsToScrub);
        credentials.addAll(checkCredentialEnvVars);
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
        return new ContainerRunSupport(runner, cloneDir, taskId, environments, segments, sandboxLifecyclePass);
    }
}
