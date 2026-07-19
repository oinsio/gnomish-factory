package com.github.oinsio.gnomish.e2e.gitea

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import spock.lang.AutoCleanup
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * Proves the Gitea container harness itself works (task 6.5, FR11, G2 infra): container starts,
 * bootstraps an HTTP-auth remote, and a real {@code git push} from a local clone succeeds against
 * it — the pushed ref is then visible back via the Gitea API. This is NOT task 6.6's product
 * scenarios (best-effort push after rounds, cross-instance resume) — purely "does the harness
 * work," for those later specs to build on.
 *
 * <p>The container is {@code @Shared} across this spec's features (Gitea startup is slow); see
 * {@link GiteaContainerFixture}'s doc comment for the shared-fixture rationale. Every feature
 * shares {@link GiteaAvailability#dockerAvailable()}'s class-level {@code @IgnoreIf} skip so a
 * Docker-less environment (most CI runners without dind, some sandboxes) sees this suite reported
 * as SKIPPED with a clear reason, never a failure — {@code .claude/rules/testing.md} treats Docker
 * as a dev/CI prerequisite for this layer specifically.
 *
 * <p>Implements FR11 of add-git-workflow (G2 infra).
 */
@Timeout(value = 180, unit = java.util.concurrent.TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unreachable — see GiteaAvailability; Docker is a dev/CI prerequisite for the Gitea E2E layer (.claude/rules/testing.md)')
class GiteaContainerFixtureSmokeSpec extends Specification implements BareGitRepoFixture {

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture()

    @TempDir
    Path tempDir

    def setupSpec() {
        gitea.start()
    }

    def "the container starts and serves the Gitea API"() {
        expect: 'the admin token is a bootstrapped, non-blank credential'
        gitea.adminToken() != null
        !gitea.adminToken().isBlank()
    }

    def "a local clone can push to the container over the HTTP-auth remote"() {
        given: 'a local working repo with one commit'
        Path work = initWorkingRepo(tempDir)
        GitProcessRunner git = new GitProcessRunner()
        git.run(work, 'checkout', '-q', '-b', 'main')
        git.run(work, 'config', 'user.email', 'e2e@example.invalid')
        git.run(work, 'config', 'user.name', 'e2e')
        git.run(work, 'commit', '-q', '--allow-empty', '-m', 'smoke commit')

        when: 'the remote is pointed at the bootstrapped Gitea repo and pushed'
        git.run(work, 'remote', 'add', 'origin', gitea.authenticatedCloneUrl())
        def result = git.run(work, 'push', 'origin', 'main')

        then: 'the push succeeds'
        result.exitCode() == 0

        and: 'the pushed ref is visible via the Gitea API (a short poll absorbs a post-push settle delay)'
        new PollingConditions(timeout: 10, initialDelay: 0.5, delay: 0.5).eventually {
            assert branchExists('main')
        }
    }

    private boolean branchExists(String branch) {
        HttpClient client = HttpClient.newHttpClient()
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("${gitea.apiBaseUrl()}/repos/${GiteaContainerFixture.ADMIN_USER}"
                + "/${GiteaContainerFixture.REPO_NAME}/branches/${branch}"))
                .header('Authorization', "token ${gitea.adminToken()}")
                .GET()
                .build()
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
        response.statusCode() == 200
    }
}
