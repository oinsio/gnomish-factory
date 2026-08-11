package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.SandboxProperties
import com.github.oinsio.gnomish.adapter.engine.SystemClock
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper
import com.github.oinsio.gnomish.adapter.environment.ChildEnvAllowlist
import com.github.oinsio.gnomish.adapter.environment.ContainerEnvironments
import com.github.oinsio.gnomish.adapter.environment.ExecCommand
import com.github.oinsio.gnomish.adapter.environment.GuardImageAvailability
import com.github.oinsio.gnomish.adapter.environment.SelfCheckFailedException
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * M2 + M3 of add-sandbox-core (task 9.3), over the production {@link
 * ContainerEnvironments} seam — exactly what the container-mode runner
 * materializes through:
 *
 * <ul>
 *   <li><b>M2 (fail-closed self-check):</b> a task network that silently lost
 *       its {@code --internal} flag — D5's "silent protection degradation"
 *       class — is caught by the mandatory self-check before any gnome-product
 *       process executes, and the thrown probe failure names the probe (UX2);
 *   <li><b>M3 (no secrets in the box):</b> the environment observed inside a
 *       healthy box contains only the image's own {@code ENV} plus the
 *       factory-set proxy fragment — no tracker token, no host-inherited
 *       variable of the factory process.
 * </ul>
 *
 * <p>Implements M2, M3, FR8, FR9, NFR-S1 of add-sandbox-core.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
@IgnoreIf(
value = {
    !GuardImageAvailability.available()
},
reason = 'Docker daemon or guard image unavailable — Docker is a dev/CI prerequisite for the container E2E layer')
class ContainerModeIsolationE2ESpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def gitRunner = new GitProcessRunner()
    Path cloneDir
    String key

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'iso-project')
        Files.writeString(cloneDir.resolve('seed.txt'), 'seed\n')
        gitRunner.run(cloneDir, 'add', 'seed.txt')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        gitRunner.run(cloneDir, 'branch', 'gnomish/iso-task')
    }

    def cleanup() {
        ContainerE2eDocker.removeTaskObjects(key)
    }

    private ContainerEnvironments environments(String taskKey) {
        key = taskKey
        ContainerEnvironments.forTask(
                taskKey,
                cloneDir,
                new ContainerHarvestFetch(gitRunner, cloneDir),
                new SandboxProperties(FakeAgentSandboxImage.ensureBuilt('plain-round'), null, null, null, [], [], false),
                new SystemClock(),
                ChildEnvAllowlist.none(),
                new ThreadSleeper(),
                tempDir.resolve('guard-config'))
    }

    // M2: the self-check catches broken isolation fail-closed — no gnome process may run in a
    // box whose task network is not internal-only.
    def "a task network without the internal flag fails the self-check before any gnome process"() {
        given: 'a pre-existing task network created WITHOUT --internal (the degradation D5 names)'
        def taskKey = "iso-broken-${System.nanoTime()}"
        new ProcessBuilder('docker', 'network', 'create',
                '--label', 'com.github.oinsio.gnomish.factory=true',
                '--label', "com.github.oinsio.gnomish.task=${taskKey}".toString(),
                "gnomish-net-${taskKey}".toString()).start().waitFor()

        when: 'the environment materializes (create() reuses the surviving network)'
        environments(taskKey).roundEnvironment().materialize('gnomish/iso-task', null)

        then: 'the self-check rejects the environment, naming the failed probe (UX2)'
        def failure = thrown(SelfCheckFailedException)
        failure.message.contains('direct-egress') || failure.message.contains('isolation')
    }

    // M3: the box environment holds no tracker token and no host-inherited variable — only the
    // image ENV plus the factory-set proxy fragment survive into an exec child.
    def "the in-box environment contains no tracker token and no host-inherited variables"() {
        given: 'a healthy materialized guarded environment'
        def taskKey = "iso-env-${System.nanoTime()}"
        def environment = environments(taskKey).roundEnvironment()
        environment.materialize('gnomish/iso-task', null)

        when: 'the full environment is dumped from inside the box'
        def handle = environment.exec(new ExecCommand(['env'], [:], null, true))
        def output = new String(handle.output().readAllBytes(), StandardCharsets.UTF_8)
        handle.waitForExit()
        def names = output.readLines().findAll { it.contains('=') }.collect { it.substring(0, it.indexOf('=')) }

        then: 'every observed variable is either image ENV or the factory-set proxy fragment'
        def allowed = [
            'PATH',
            'HOME',
            'HOSTNAME',
            'PWD',
            'OLDPWD',
            'SHLVL',
            'TERM',
            'CHARSET',
            'LC_COLLATE',
            'GNOMISH_FAKE_SCENARIO',
            'HTTP_PROXY',
            'HTTPS_PROXY',
            'http_proxy',
            'https_proxy',
        ] as Set
        names.every { allowed.contains(it) }

        and: 'no tracker or external-check credential name appears (NFR-S1)'
        !names.contains('GNOMISH_GITHUB_TOKEN')
        !names.contains('GNOMISH_GITHUB_ACTIONS_TOKEN')

        and: 'not one of the factory process own env vars leaked in (M3)'
        def hostOnly = System.getenv().keySet() - allowed
        names.intersect(hostOnly).isEmpty()
    }
}
