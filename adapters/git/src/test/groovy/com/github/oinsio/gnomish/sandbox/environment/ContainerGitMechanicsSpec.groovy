package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.adapter.git.AdmittedHarvest
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.EnvironmentSalvage
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.e2e.gitea.GiteaAvailability
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.InstantSource
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Task 5.5, M4 of add-sandbox-core: the container git mechanics against a real
 * daemon and real local repositories — clone independence (no hardlinks, no
 * remote, no server address in the box), the fast-forward-only harvest with its
 * rewritten-history refusal, hooks never crossing the boundary, the salvage
 * paths (surviving volume, lost environment), and resume by a second factory
 * instance from the branch alone.
 *
 * <p>Implements FR3, FR5, FR6, M4 of add-sandbox-core; FR6 of own-git-transfer-argv.
 */
@IgnoreIf({
    !GiteaAvailability.dockerAvailable()
})
class ContainerGitMechanicsSpec extends Specification implements BareGitRepoFixture {

    static final String TASK = 'mech-1'
    static final String BRANCH = 'gnomish/mech-1'
    static final ResourceLimits LIMITS = new ResourceLimits('2', '512m', 256L, '10g')

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    private final InstantSource clock = new VirtualClock()
    private final List<ContainerTaskExecutionEnvironment> envs = []

    def setupSpec() {
        GitSandboxImage.ensureBuilt()
    }

    def cleanup() {
        envs.each { it.dispose() }
    }

    private Path factoryClone(String name = 'factory-clone') {
        def clone = initWorkingRepo(tempDir, name)
        new File(clone.toFile(), 'seed.txt').text = 'seed'
        commitAll(clone)
        gitOutput(clone, 'branch', BRANCH)
        // The branch starts at a registered closing commit, as STARTED leaves it (design D13).
        gitOutput(clone, 'update-ref', 'refs/gnomish-factory/' + TASK, 'refs/heads/' + BRANCH)
        clone
    }

    private ContainerTaskExecutionEnvironment env(Path source, String key = 'mech-' + System.nanoTime()) {
        def e = new ContainerTaskExecutionEnvironment(
                BoxEquipmentFixture.forBox(new DockerCli(), GitSandboxImage.IMAGE, LIMITS, clock),
                key, new BoxGitLink(source, new ContainerHarvestFetch(runner, source)), ChildEnvAllowlist.none(), 'proj-1')
        envs << e
        e
    }

    private static String inBox(ContainerTaskExecutionEnvironment e, String script) {
        def handle = e.exec(new ExecCommand(['sh', '-c', script], [:], null, true))
        def out = new String(handle.output().readAllBytes(), StandardCharsets.UTF_8)
        def code = handle.waitForExit()
        assert code == 0: "in-box script failed (${code}): ${script}\n${out}"
        out.trim()
    }

    private static String gnomeCommit(ContainerTaskExecutionEnvironment e, String file = 'work.txt', String content = 'gnome work') {
        inBox(e, "echo '${content}' > ${file} && git add -A && git commit -m 'gnome commit'")
        inBox(e, 'git rev-parse HEAD')
    }

    // Scenario: git/git-task-persistence / No push machinery inside the box
    // Scenario: git/git-task-persistence / No shared objects with the factory clone
    // Scenario: git/git-task-persistence / The box holds no way to the server
    def "FR3: the in-box clone shares no hardlinked objects and holds no remote or server address"() {
        given:
        def source = factoryClone()
        def e = env(source)
        e.materialize(BRANCH, null)

        expect: 'no object file has a second link — in-box corruption cannot reach the factory repository'
        inBox(e, 'find .git/objects -type f -links +1 | wc -l') == '0'

        and: 'no alternates redirection into the factory object store'
        inBox(e, 'test ! -f .git/objects/info/alternates && echo absent') == 'absent'

        and: 'no remote at all — the only path to origin is the factory-side push after harvest'
        inBox(e, 'git remote | wc -l') == '0'

        and: 'the working copy is checked out on the task branch with the agent identity'
        inBox(e, 'git symbolic-ref --short HEAD') == BRANCH
        inBox(e, 'git config user.name') == 'gnome'
        inBox(e, 'git config gc.auto') == '0'
    }

    // Scenario: git/git-task-persistence / The branch moves only through the admission
    def "FR5: harvest brings an in-box gnome commit to the factory clone's quarantine, and the admission onto the branch"() {
        given:
        def source = factoryClone()
        def branchBefore = gitOutput(source, 'rev-parse', 'refs/heads/' + BRANCH)
        def e = env(source)
        e.materialize(BRANCH, null)
        def inBoxTip = gnomeCommit(e)

        when:
        e.harvest()

        then: 'the harvest moved the quarantine alone'
        gitOutput(source, 'rev-parse', 'refs/gnomish-quarantine/' + TASK) == inBoxTip
        gitOutput(source, 'rev-parse', 'refs/heads/' + BRANCH) == branchBefore

        when:
        def verdict = AdmittedHarvest.admit(source, TASK)

        then: 'the admission moved the factory branch to the in-box tip'
        AdmittedHarvest.admitted(verdict)
        gitOutput(source, 'rev-parse', 'refs/heads/' + BRANCH) == inBoxTip

        and: 'the content is readable as a bare object without any checkout'
        gitOutput(source, 'show', BRANCH + ':work.txt') == 'gnome work'
    }

    // FR6 of own-git-transfer-argv: the harvest is the owner's Container transfer, so a tag the gnome
    //     creates inside the real box — pointing into the very history the harvest delivers — never
    //     auto-follows into the factory clone, and the harvest leaves no FETCH_HEAD behind.
    // Scenario: git/git-task-persistence / A box-authored tag does not cross the boundary
    def "FR6: a tag created in the box does not appear in the factory clone after harvest"() {
        given: 'a gnome commit in the box, tagged there'
        def source = factoryClone()
        def e = env(source)
        e.materialize(BRANCH, null)
        def inBoxTip = gnomeCommit(e)
        inBox(e, 'git tag box-made HEAD')

        when:
        e.harvest()

        then: 'the branch arrived in the quarantine, the tag did not, and FETCH_HEAD was not written'
        gitOutput(source, 'rev-parse', 'refs/gnomish-quarantine/' + TASK) == inBoxTip
        gitExitCode(source, 'rev-parse', '--verify', '--quiet', 'refs/tags/box-made') != 0
        gitOutput(source, 'tag', '--list') == ''
        !new File(source.toFile(), '.git/FETCH_HEAD').exists()
    }

    // Scenario: git/git-task-persistence / History rewrite aborts
    // Scenario: git/git-task-persistence / Rewritten history is refused at the boundary
    // Scenario: git/git-task-persistence / Refused content is not durable
    def "FR5: rewritten history inside the box is refused by the admission and the factory ref is unchanged"() {
        given: 'one harvested and admitted commit, then an in-box amend of that same tip'
        def source = factoryClone()
        def e = env(source)
        e.materialize(BRANCH, null)
        def harvested = gnomeCommit(e)
        e.harvest()
        assert AdmittedHarvest.admitted(AdmittedHarvest.admit(source, TASK))
        inBox(e, "git commit --amend -m 'rewritten'")

        when:
        e.harvest()
        def verdict = AdmittedHarvest.admit(source, TASK)

        then: 'the rewritten line reached the quarantine only, and the admission refused it'
        AdmittedHarvest.refused(verdict)
        gitOutput(source, 'rev-parse', 'refs/heads/' + BRANCH) == harvested
        gitOutput(source, 'rev-parse', 'refs/gnomish-factory/' + TASK) != gitOutput(source, 'rev-parse', 'refs/gnomish-quarantine/' + TASK)
    }

    // Scenario: git/git-task-persistence / Hooks do not cross the boundary
    def "FR5: hooks installed inside the box never become active in the factory clone"() {
        given: 'a poison pre-commit hook installed in the in-box clone'
        def source = factoryClone()
        def e = env(source)
        e.materialize(BRANCH, null)
        inBox(e, "printf '#!/bin/sh\\ntouch /tmp/poisoned\\n' > .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit")
        gnomeCommit(e)

        when:
        e.harvest()

        then: 'harvest transferred branch content only — no hook file appeared factory-side'
        !new File(source.toFile(), '.git/hooks/pre-commit').exists()
    }

    // Scenario: git/git-task-persistence / Volume survives a dead container
    def "FR6: a surviving stopped container is reattached and its leftovers salvaged onto the branch"() {
        given: 'uncommitted in-box work, then the container stops (kept environment / dead process)'
        def source = factoryClone()
        def key = 'mech-salvage-' + System.nanoTime()
        def e1 = env(source, key)
        e1.materialize(BRANCH, null)
        inBox(e1, "echo 'half-done' > tail.txt")
        assert new ProcessBuilder('docker', 'stop', 'gnomish-box-' + key).start().waitFor() == 0

        when: 'a second adapter instance reattaches and salvages through the port'
        def e2 = env(source, key)
        e2.materialize(BRANCH, null)
        new EnvironmentSalvage(e2, ClaimEpochSource.NONE, new GitProcessRunner(), source).salvage(TASK)

        then: 'the salvage commit reached the factory branch with the interrupted tail'
        gitOutput(source, 'show', BRANCH + ':tail.txt') == 'half-done'
        gitOutput(source, 'log', '-1', '--format=%s', BRANCH) == 'gnomish: salvage'
    }

    // Scenario: git/git-task-persistence / Environment lost entirely
    def "FR6: a fully lost environment degrades salvage to a no-op and resume materializes fresh from the branch"() {
        given: 'harvested work, then container and volume vanish behind the adapter'
        def source = factoryClone()
        def key = 'mech-lost-' + System.nanoTime()
        def e1 = env(source, key)
        e1.materialize(BRANCH, null)
        def tip = gnomeCommit(e1)
        e1.harvest()
        assert AdmittedHarvest.admitted(AdmittedHarvest.admit(source, TASK))
        inBox(e1, "echo 'to-be-lost' > lost.txt")
        assert new ProcessBuilder('docker', 'rm', '-f', 'gnomish-box-' + key).start().waitFor() == 0

        when: 'salvage runs against the dead box'
        new EnvironmentSalvage(e1, ClaimEpochSource.NONE, new GitProcessRunner(), source).salvage(TASK)

        then: 'nothing thrown, at most the uncommitted tail is lost, recorded rounds intact'
        noExceptionThrown()
        gitOutput(source, 'rev-parse', 'refs/heads/' + BRANCH) == tip

        when: 'a fresh environment materializes from the branch state alone'
        assert new ProcessBuilder('docker', 'volume', 'rm', '-f', 'gnomish-vol-' + key).start().waitFor() == 0
        def e2 = env(source, key)
        e2.materialize(BRANCH, null)

        then:
        inBox(e2, 'git rev-parse HEAD') == tip
        inBox(e2, 'cat work.txt') == 'gnome work'
        inBox(e2, 'test ! -f lost.txt && echo gone') == 'gone'
    }

    // Scenario: git/git-task-persistence / Another instance resumes from origin
    def "M4: a second factory instance resumes from the branch alone via origin"() {
        given: 'instance A harvests gnome work and pushes to a bare origin'
        def origin = initBareRepo(tempDir, 'origin.git')
        def sourceA = factoryClone('factory-a')
        addRemote(sourceA, 'origin', origin.toString())
        def eA = env(sourceA)
        eA.materialize(BRANCH, null)
        def tip = gnomeCommit(eA)
        eA.harvest()
        assert AdmittedHarvest.admitted(AdmittedHarvest.admit(sourceA, TASK))
        gitOutput(sourceA, 'push', 'origin', BRANCH + ':' + BRANCH)

        when: 'instance B, a different machine, fetches exactly that branch and materializes'
        def sourceB = initWorkingRepo(tempDir, 'factory-b')
        new File(sourceB.toFile(), 'seed.txt').text = 'seed'
        commitAll(sourceB)
        addRemote(sourceB, 'origin', origin.toString())
        assert fetchFromOrigin(sourceB, 'refs/heads/' + BRANCH + ':refs/heads/' + BRANCH).exitCode() == 0
        def eB = env(sourceB)
        eB.materialize(BRANCH, null)

        then: 'the new environment continues from the recorded branch state'
        inBox(eB, 'git rev-parse HEAD') == tip
        inBox(eB, 'cat work.txt') == 'gnome work'
    }
}
