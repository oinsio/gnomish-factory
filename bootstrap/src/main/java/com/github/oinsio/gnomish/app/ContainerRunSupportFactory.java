package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.Segment;
import com.github.oinsio.gnomish.sandbox.environment.ContainerEnvironments;
import java.nio.file.Path;
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
     * @param checkCredentialEnvVars the credential names the configured check providers declared
     *     through the SPI (FR17, design D11 of add-plugin-architecture), resolved once by the
     *     composition root — no vendor constant is named here
     * @param credentialEnvVarsToScrub the active tracker adapter's declared credential names;
     *     empty for plain {@code gnomish run}
     */
    static ContainerRunSupport create(
            Path cloneDir,
            String taskId,
            List<Segment> segments,
            SandboxProperties sandboxProperties,
            List<String> checkCredentialEnvVars,
            List<String> credentialEnvVarsToScrub) {
        var runner = new GitProcessRunner();
        List<String> credentials = new ArrayList<>(credentialEnvVarsToScrub);
        credentials.addAll(checkCredentialEnvVars);
        var allowlist = ChildEnvAllowlist.of(sandboxProperties.envPassthrough(), credentials);
        var environments = ContainerEnvironments.forTask(
                TaskIdSanitizer.sanitize(taskId),
                cloneDir,
                new ContainerHarvestFetch(runner, cloneDir),
                sandboxProperties,
                new SystemClock(),
                allowlist,
                new ThreadSleeper(),
                Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir")), "gnomish-guard"));
        return new ContainerRunSupport(runner, cloneDir, taskId, environments, segments);
    }
}
