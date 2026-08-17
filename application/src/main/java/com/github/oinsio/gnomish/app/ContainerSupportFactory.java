package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
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

    /**
     * Builds the run's sandbox support, bound to the task branch that already exists.
     *
     * <p>{@code definition} is passed because the container environments compose their child
     * environment before the run assembly exists, and the credential names to scrub are no longer
     * knowable from configuration alone: the built-in {@code http} check provider takes its
     * credential name from each check's own manifest params (FR11, FR17, design D11 of
     * add-plugin-architecture). The composition root's binding is what reads them out — through the
     * discovered registry, over params core never interprets — so this seam only has to carry the
     * pipeline that names them.
     */
    SandboxRunSupport create(
            Path cloneDir,
            String taskId,
            List<Segment> segments,
            SandboxProperties sandboxProperties,
            FactoryProperties factoryProperties,
            PipelineDefinition definition,
            List<String> credentialEnvVarsToScrub);
}
