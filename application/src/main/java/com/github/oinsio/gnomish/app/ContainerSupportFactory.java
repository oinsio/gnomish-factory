package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.sandbox.Segment;
import java.nio.file.Path;
import java.util.List;

/**
 * The construction seam container runners resolve their per-run {@link SandboxRunSupport}
 * through: the composition root binds the container support bundle's own factory, daemon-free
 * specs bind one whose environments run over a scripted fake docker CLI — the same seam discipline
 * as the docker environments' own package-private constructor. The runners never name the
 * realization (task 4.4, D12 of split-into-modules).
 *
 * <p>Implements FR6, FR21, FR25 of add-sandbox-core (testability seam, no behavioral change).
 */
@FunctionalInterface
interface ContainerSupportFactory {

    /** Builds the run's sandbox support, bound to the task branch that already exists. */
    SandboxRunSupport create(
            Path cloneDir,
            String taskId,
            List<Segment> segments,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties,
            List<String> credentialEnvVarsToScrub);
}
