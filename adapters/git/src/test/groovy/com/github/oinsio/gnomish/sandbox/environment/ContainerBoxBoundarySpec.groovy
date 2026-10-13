package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR3, FR10 of add-sandbox-core against a real daemon: what a box holds and what it can
 * reach — a working copy pinned at a factory-chosen commit, a scratch area that never rides a
 * harvest and dies with the box, factory writes landing as the image's task user, and a runaway
 * process stopped by the box's own limits while the box and the factory carry on.
 *
 * <p>Docker-gated like the other container suites ({@code ContainerGitMechanicsSpec}); each
 * feature materializes a uniquely keyed box and {@code cleanup()} disposes it.
 */
@IgnoreIf({
    !GiteaAvailability.dockerAvailable()
})
class ContainerBoxBoundarySpec extends Specification implements BareGitRepoFixture {

    static final String BRANCH = 'gnomish/box-1'
    static final ResourceLimits LIMITS = new ResourceLimits('1', '64m', 32L, '10g')

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()
    private final List<ContainerTaskExecutionEnvironment> envs = []
    private Path clone

    def setupSpec() {
        GitSandboxImage.ensureBuilt()
    }

    def setup() {
        clone = initWorkingRepo(tempDir, 'factory-clone')
        commit(clone, 'seed.txt', 'seed')
        gitOutput(clone, 'branch', BRANCH)
    }

    def cleanup() {
        envs.each { it.dispose() }
    }

    private ContainerTaskExecutionEnvironment box(String key = 'bnd-' + System.nanoTime()) {
        def e = new ContainerTaskExecutionEnvironment(
                BoxEquipmentFixture.forBox(new DockerCli(), GitSandboxImage.IMAGE, LIMITS, new VirtualClock()),
                key, new BoxGitLink(clone, new ContainerHarvestFetch(runner, clone)), ChildEnvAllowlist.none(), 'proj-1')
        envs << e
        e
    }

    private static Map inBox(ContainerTaskExecutionEnvironment e, String script) {
        def handle = e.exec(new ExecCommand(['sh', '-c', script], [:], null, true))
        def out = new String(handle.output().readAllBytes(), StandardCharsets.UTF_8).trim()
        [exit: handle.waitForExit(), out: out]
    }

    // Scenario: sandbox/execution-environment / Materialize pinned at a commit
    def "FR1: a box materialized at a pin holds that commit's tree, without the branch's later commits"() {
        given: 'the task branch carries a commit after the pin'
        def pin = gitOutput(clone, 'rev-parse', BRANCH)
        gitOutput(clone, 'checkout', '-q', BRANCH)
        commit(clone, 'later.txt', 'after the pin')
        gitOutput(clone, 'checkout', '-q', '-')

        when:
        def e = box()
        e.materialize(BRANCH, pin)

        then: 'HEAD is the pin, its file is there, and the later commit\'s file is not'
        inBox(e, 'git rev-parse HEAD').out == pin
        inBox(e, 'cat seed.txt').out == 'seed'
        inBox(e, 'test -e later.txt').exit != 0
    }

    // Scenario: sandbox/execution-environment / Scratch content is never harvested and ends with dispose
    def "FR1: a scratch protocol file rides no harvest, and the scratch area is gone after dispose"() {
        given: 'a box with a protocol file in its scratch area and a gnome commit in its working copy'
        def key = 'bnd-' + System.nanoTime()
        def e = box(key)
        e.materialize(BRANCH, null)
        e.putFile(e.scratchRoot() + '/findings.json', '{"findings":[]}'.getBytes(StandardCharsets.UTF_8))
        assert inBox(e, "echo work > work.txt && git add -A && git commit -q -m 'gnome commit'").exit == 0

        when:
        e.harvest()

        then: 'the harvest — in the task\'s quarantine (design D18) — carries the gnome\'s work and no scratch content'
        def files = gitOutput(clone, 'ls-tree', '-r', '--name-only', 'refs/gnomish-quarantine/box-1').readLines()
        files.contains('work.txt')
        !files.any { it.contains('findings') }

        when:
        e.dispose()

        then: 'the container holding the scratch area no longer exists'
        !new DockerCli().run([
            'inspect',
            FactoryDockerLabels.containerName(key)
        ]).ok()
    }

    // Scenario: sandbox/execution-environment / Factory writes run as the task user
    def "FR1: a file the factory writes and a command it runs in the box belong to the image's task user, not root"() {
        given:
        def e = box()
        e.materialize(BRANCH, null)

        when:
        e.putFile('written.txt', 'x'.getBytes(StandardCharsets.UTF_8))
        e.putFile(e.scratchRoot() + '/protocol.json', '{}'.getBytes(StandardCharsets.UTF_8))

        then: 'the process runs as uid 1000 (the image\'s gnome user), and both files are owned by it'
        inBox(e, 'id -u').out == '1000'
        inBox(e, 'stat -c %u written.txt').out == '1000'
        inBox(e, "stat -c %u ${e.scratchRoot()}/protocol.json").out == '1000'
    }

    // Scenario: sandbox/execution-environment / Runaway build is contained
    def "FR10: a process past the memory or PID limit is killed in the box, the exec fails, and the box carries on"() {
        given:
        def e = box()
        e.materialize(BRANCH, null)

        when: 'a process grows past the memory limit'
        def memory = inBox(e, "awk 'BEGIN{s=\"x\"; while(1) s=s s}'")

        then: 'it is killed inside the box and the exec reports a failure'
        memory.exit == 137

        when: 'a process forks past the PID limit'
        def forks = inBox(e, 'i=0; while [ $i -lt 100 ]; do sleep 1 & i=$((i+1)); done; wait')

        then: 'the fork is refused inside the box and the exec reports a failure'
        forks.exit != 0
        forks.out.contains("can't fork")

        and: 'the box answers again once its short-lived children are gone, and the factory clone is untouched'
        (1..100).any { inBox(e, 'echo alive').out == 'alive' }
        gitOutput(clone, 'status', '--porcelain').isEmpty()
    }
}
