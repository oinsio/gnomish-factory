package com.github.oinsio.gnomish.e2e.gitea

import com.github.oinsio.gnomish.adapter.check.github.GithubCheckExternalClient
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckWorkspace
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.domain.engine.PollStatus
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import spock.lang.AutoCleanup
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * Task 7.1, M1 of add-external-check-github-actions: proves the Testcontainers Gitea + Actions
 * runner fixture actually works end to end — a real {@code act_runner} container executes a real
 * workflow after a push, and the run is reachable through this change's own {@link
 * GithubCheckExternalClient} adapter (the exact query path production code uses), not a
 * hand-rolled REST probe. This is a FIXTURE smoke proof only; task 7.2 builds the pass/fail stage
 * assertions on top of it.
 *
 * <p>Real container startup (Gitea + act_runner) plus a real Actions run is slow and inherently
 * async (runner registration, job pickup, image pull) — the poll loop below uses a generous real
 * sleep and timeout rather than assuming instant availability.
 *
 * <p>Implements M1 of add-external-check-github-actions.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unreachable — see GiteaAvailability; Docker is a dev/CI prerequisite for the Gitea E2E layer (.claude/rules/testing.md)')
class GiteaActionsRunnerE2ESpec extends Specification implements BareGitRepoFixture {

    private static final String CHECK_ID = '.gitea/workflows/smoke.yml'
    private static final String WORKFLOW_YAML = '''\
        name: smoke
        on: [push]
        jobs:
          smoke:
            runs-on: ubuntu-latest
            steps:
              - run: echo ok
        '''.stripIndent()

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture(true)

    @Shared
    @AutoCleanup('stop')
    GiteaActionsRunnerFixture runner

    @TempDir
    Path tempDir

    def setupSpec() {
        gitea.start()
        runner = new GiteaActionsRunnerFixture(gitea, gitea.createRunnerRegistrationToken())
        runner.start()
    }

    // M1: pushing the attempt commit's workflow file triggers a real run, observed here through
    // the same adapter production code polls with — proving the fixture, not just Gitea's API.
    def "pushing a commit with a workflow triggers a run that reaches a successful conclusion"() {
        given: 'a working repo with the smoke workflow, pushed to the bootstrapped Gitea repo'
        Path work = initWorkingRepo(tempDir, 'actions-project')
        Files.createDirectories(work.resolve('.gitea/workflows'))
        Files.writeString(work.resolve(CHECK_ID), WORKFLOW_YAML)
        def git = new GitProcessRunner()
        git.run(work, 'checkout', '-q', '-b', 'main')
        git.run(work, 'add', '.')
        git.run(work, '-c', 'user.email=e2e@example.invalid', '-c', 'user.name=e2e', 'commit', '-q', '-m', 'add workflow')
        git.run(work, 'remote', 'add', 'origin', gitea.authenticatedCloneUrl())

        when: 'the commit is pushed'
        def headSha = git.run(work, 'rev-parse', 'HEAD').stdout().trim()
        def pushResult = git.run(work, 'push', 'origin', 'main')

        then: 'the push itself succeeds'
        pushResult.exitCode() == 0

        and: 'the adapter this change built eventually observes the run passing (real runner, real async delay)'
        def httpClient = new GithubHttpClient(gitea.apiBaseUrl(), gitea.adminToken())
        def client = new GithubCheckExternalClient(httpClient)
        def check = new VerifyCheck.External(
                CHECK_ID, Duration.ofSeconds(5), Duration.ofMinutes(5), VerifyCheck.TimeoutClass.QUALITY)
        def workspace = new GithubCheckWorkspace(GiteaContainerFixture.ADMIN_USER, GiteaContainerFixture.REPO_NAME, headSha)

        new PollingConditions(timeout: 480, initialDelay: 5, delay: 5).eventually {
            def status = client.poll(check, workspace)
            assert status instanceof PollStatus.Pass: "latest poll was ${status} — runner logs: ${runner.logs()}"
        }
    }
}
