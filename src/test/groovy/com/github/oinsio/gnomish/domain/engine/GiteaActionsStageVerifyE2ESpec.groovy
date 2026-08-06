package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.adapter.check.github.GithubCheckExternalClient
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckWorkspace
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.e2e.gitea.GiteaActionsRunnerFixture
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.e2e.gitea.GiteaContainerFixture
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

/**
 * Task 7.2 (M1, G1, G4 of add-external-check-github-actions): the capstone live stage-level E2E.
 * Building on task 7.1's proven Gitea + {@code act_runner} fixture, this drives the real {@link
 * GithubCheckExternalClient} adapter <em>through the engine's own verify/external-poll
 * orchestration</em> — the real {@link VerifyOrchestrator} + {@link ExternalPolling} with a
 * production {@link SystemClock}/{@link ThreadSleeper}, exactly as the engine wires them in
 * production — rather than calling the adapter directly like 7.1 did. Two attempt commits are
 * pushed on distinct SHAs (the adapter matches runs by head SHA): a green workflow and a red one.
 *
 * <p>Green: the stage's external check verifies to a stage {@link Verdict.Pass}, no findings, with
 * zero manual steps. Red: the same verify path yields a {@link Verdict.Fail} whose findings — the
 * report the funnel produces — name the failed job and step and carry a log tail (FR6). Both
 * verdicts are produced by the engine's poll loop blocking on the real runner until the run
 * concludes; the {@code @Timeout} guards the whole spec.
 *
 * <p>Implements M1, G1, G4 of add-external-check-github-actions; the red findings assert FR6.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GiteaAvailability.dockerAvailable()
},
reason = 'Docker daemon unreachable — see GiteaAvailability; Docker is a dev/CI prerequisite for the Gitea E2E layer (.claude/rules/testing.md)')
class GiteaActionsStageVerifyE2ESpec extends Specification implements BareGitRepoFixture {

    private static final String CHECK_ID = '.gitea/workflows/ci.yml'
    private static final TaskContext CONTEXT = new TaskContext('CI-1', 'title', 'body', [])
    private static final AttemptKey KEY = new AttemptKey('CI-1', 'verify', 0)

    private static final String GREEN_YAML = '''\
        name: ci
        on: [push]
        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - run: echo ok
        '''.stripIndent()

    // A named job (build) and a named failing step (run-tests) so the red-case findings can be
    // asserted by name — FR6's "failed jobs/steps land in the report".
    private static final String RED_YAML = '''\
        name: ci
        on: [push]
        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - name: run-tests
                run: exit 1
        '''.stripIndent()

    @Shared
    @AutoCleanup('stop')
    GiteaContainerFixture gitea = new GiteaContainerFixture(true)

    @Shared
    @AutoCleanup('stop')
    GiteaActionsRunnerFixture runner

    @Shared
    String greenSha

    @Shared
    String redSha

    @TempDir
    @Shared
    Path tempDir

    def setupSpec() {
        gitea.start()
        runner = new GiteaActionsRunnerFixture(gitea, gitea.createRunnerRegistrationToken())
        runner.start()

        Path work = initWorkingRepo(tempDir, 'ci-project')
        Files.createDirectories(work.resolve('.gitea/workflows'))
        def git = new GitProcessRunner()
        git.run(work, 'checkout', '-q', '-b', 'main')
        git.run(work, 'remote', 'add', 'origin', gitea.authenticatedCloneUrl())
        // Two attempt commits on a linear history: each push is a distinct head SHA, so the two
        // runs are keyed independently and the adapter matches each by its own commit (see 7.1).
        greenSha = pushWorkflow(work, git, GREEN_YAML, 'green attempt commit')
        redSha = pushWorkflow(work, git, RED_YAML, 'red attempt commit')
    }

    // M1, G1, G4: the external CI check, driven through the engine's verify path against the live
    // green run, yields a stage Pass with no findings — fully automated, no manual steps.
    def "a stage whose external CI check concludes green verifies to a stage Pass"() {
        given: 'the stage verifies the attempt commit that carries the green workflow'
        def workspace = new GithubCheckWorkspace(GiteaContainerFixture.ADMIN_USER, GiteaContainerFixture.REPO_NAME, greenSha)

        when: 'the engine runs the verify chain, its poll loop blocking on the real runner'
        def result = orchestrator().verify([check()], CONTEXT, workspace, KEY)

        then: 'the single external check reached a stage Pass'
        result.results.size() == 1
        result.results[0].verdict instanceof Verdict.Pass
    }

    // M1, G1, G4, FR6: the same verify path against the live red run yields a stage Fail whose
    // findings — the report — name the failed job and step and carry a log tail.
    def "a stage whose external CI check concludes red fails with findings naming the job, step and a log tail"() {
        given: 'the stage verifies the attempt commit that carries the failing workflow'
        def workspace = new GithubCheckWorkspace(GiteaContainerFixture.ADMIN_USER, GiteaContainerFixture.REPO_NAME, redSha)

        when: 'the engine runs the verify chain, its poll loop blocking on the real runner'
        def result = orchestrator().verify([check()], CONTEXT, workspace, KEY)

        then: 'the single external check reached a stage Fail carrying findings'
        result.results.size() == 1
        def verdict = result.results[0].verdict
        verdict instanceof Verdict.Fail
        def findings = verdict.findings()
        !findings.isEmpty()

        and: 'a finding names the failed job and step (FR6)'
        def messages = findings*.message().join('\n')
        messages.contains('build')
        messages.contains('run-tests')

        and: 'a finding carries a non-empty log tail (FR6)'
        findings.any { it.details() != null && !it.details().isBlank() }
    }

    private VerifyOrchestrator orchestrator() {
        def client = new GithubCheckExternalClient(new GithubHttpClient(gitea.apiBaseUrl(), gitea.adminToken()))
        def clock = new SystemClock()
        def polling = new ExternalPolling(client, clock, new ThreadSleeper())
        new VerifyOrchestrator(
                new ScriptedBuiltinCheckRunner(),
                new ScriptedCommandCheckRunner(),
                polling,
                new JudgeVoting(new ScriptedJudgeVoter()),
                clock,
                new RecordingEventListener())
    }

    private static VerifyCheck.External check() {
        new VerifyCheck.External(CHECK_ID, Duration.ofSeconds(5), Duration.ofMinutes(8), VerifyCheck.TimeoutClass.QUALITY)
    }

    private String pushWorkflow(Path work, GitProcessRunner git, String yaml, String message) {
        Files.writeString(work.resolve(CHECK_ID), yaml)
        git.run(work, 'add', '.')
        git.run(work, '-c', 'user.email=e2e@example.invalid', '-c', 'user.name=e2e', 'commit', '-q', '-m', message)
        def sha = git.run(work, 'rev-parse', 'HEAD').stdout().trim()
        def push = git.run(work, 'push', 'origin', 'main')
        assert push.exitCode() == 0: "push failed: ${push.stderr()}"
        sha
    }
}
