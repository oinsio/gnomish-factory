package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.file.Path
import java.time.Instant
import spock.lang.IgnoreIf
import spock.lang.TempDir

/**
 * Task 4.5, M1 of add-sandbox-core: the container adapter passes the same
 * port-level contract the host adapter passes, against a real Docker daemon — a
 * labelled internal network, working-copy volume, and keep-alive container per
 * environment, with {@code exec}, the streamed file channel, harvest, idempotent
 * dispose, and the passport all driven only through the port.
 *
 * <p>Docker-gated (mirroring the Gitea E2E layer, {@code .claude/rules/testing.md}):
 * skips cleanly when no daemon is reachable, using the same probe {@link
 * GiteaAvailability}. Each feature materializes a fresh, uniquely-keyed
 * environment so a leftover object from a crashed run never collides; {@code
 * cleanup()} in the base contract disposes it.
 *
 * <p>Implements FR3, FR4, NFR-S3, NFR-R2, M1 of add-sandbox-core.
 */
@IgnoreIf({
    !GiteaAvailability.dockerAvailable()
})
class ContainerTaskExecutionEnvironmentContractSpec extends TaskExecutionEnvironmentContract implements BareGitRepoFixture {

    static final String BRANCH = 'gnomish/contract'
    static final ResourceLimits LIMITS = new ResourceLimits('2', '512m', 256L, '10g')

    @TempDir
    Path tempDir

    private final Clock clock = { -> Instant.now() } as Clock

    def setupSpec() {
        GitSandboxImage.ensureBuilt()
    }

    @Override
    protected Optional<TaskExecutionEnvironment> arrange() {
        def source = initWorkingRepo(tempDir, 'factory-clone-' + System.nanoTime())
        new File(source.toFile(), 'seed.txt').text = 'seed'
        commitAll(source)
        gitOutput(source, 'branch', BRANCH)
        def key = 'sbx-contract-' + System.nanoTime()
        def harvester = new ContainerHarvestFetch(new GitProcessRunner(), source)
        def env = new ContainerTaskExecutionEnvironment(
                new DockerCli(), key, source, harvester, GitSandboxImage.IMAGE, 'runc', LIMITS, false, clock,
                ChildEnvAllowlist.none(), new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1'))
        env.materialize(BRANCH, null)
        Optional.of(env)
    }

    @Override
    protected String portName() {
        'TaskExecutionEnvironment (container)'
    }
}
