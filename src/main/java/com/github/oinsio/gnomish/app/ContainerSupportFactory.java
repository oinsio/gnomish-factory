package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.SandboxProperties;
import com.github.oinsio.gnomish.adapter.environment.Segment;
import java.nio.file.Path;
import java.util.List;

/**
 * The construction seam container runners resolve their per-run {@link ContainerRunSupport}
 * through: production wiring binds {@link ContainerRunSupport#create}, daemon-free specs bind a
 * factory whose {@link com.github.oinsio.gnomish.adapter.environment.ContainerEnvironments} runs
 * over a scripted fake docker CLI — the same seam discipline as {@code ContainerEnvironments}'s
 * own package-private constructor.
 *
 * <p>Implements FR6, FR21, FR25 of add-sandbox-core (testability seam, no behavioral change).
 */
@FunctionalInterface
interface ContainerSupportFactory {

    /** Builds the run's container support; see {@link ContainerRunSupport#create}. */
    ContainerRunSupport create(
            Path cloneDir,
            String taskId,
            List<Segment> segments,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties,
            List<String> credentialEnvVarsToScrub);
}
