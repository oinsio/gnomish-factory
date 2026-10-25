package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.OriginRemote
import com.github.oinsio.gnomish.app.git.ProjectIdentity
import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeUnavailableException
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.environment.DockerCommandFailedException
import com.github.oinsio.gnomish.sandbox.environment.OwnershipMode

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of add-sandbox-base-image (design D17): the startup assessment's image readiness stamps the
 * floor probe's helper {@code tracked}, under the project identity every box of the daemon's clone
 * carries, read from the clone when the assessment runs; and it really asks the runtime.
 */
class TrackedImageReadinessSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private TrackedImageReadiness readiness(Path clone, String configuredProjectId) {
        new TrackedImageReadiness(
                RegisteredCloneFixture.provider(RegisteredCloneFixture.registered(tempDir.resolve('home'), clone)),
                new SandboxProperties(null, null, null, null, null, null, false, configuredProjectId, null, null, null),
                new FactoryProperties(null, null, null, null))
    }

    def "FR13: the helper is tracked, under the configured project identity"() {
        given:
        def clone = initWorkingRepo(tempDir, 'project')

        expect:
        with(readiness(clone, 'acme-widgets').ownership()) {
            mode() == OwnershipMode.TRACKED
            projectId() == 'acme-widgets'
        }
    }

    def "FR13: without a configured identity, the clone's own origin names the project, as a box's does"() {
        given:
        def clone = initWorkingRepo(tempDir, 'project')
        Files.writeString(clone.resolve('README.md'), 'widgets\n')
        commitAll(clone, 'init')
        addOrigin(clone, tempDir)

        expect: 'the identity ContainerRunSupportFactory stamps on the boxes of this clone'
        readiness(clone, null).ownership().projectId() ==
                ProjectIdentity.resolve(null, new OriginRemote(new GitProcessRunner()).url(clone), clone)
    }

    def "FR13: the assessment asks the real runtime, which refuses an image it does not hold"() {
        given:
        def clone = initWorkingRepo(tempDir, 'project')

        when:
        readiness(clone, 'acme-widgets').assess('gnomish-test/no-such-image:absent')

        then: 'an inspect the runtime answered with a refusal, or a runtime it could not reach — never a pass'
        def failure = thrown(RuntimeException)
        failure instanceof DockerCommandFailedException || failure instanceof ContainerRuntimeUnavailableException
    }
}
