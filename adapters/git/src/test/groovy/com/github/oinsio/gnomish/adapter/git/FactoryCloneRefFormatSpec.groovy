package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.TaskGitFixture
import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * F122, F135, NFR-S2 of tighten-spec-contract (design D13, amended 2026-10-13): the factory clone
 * must be on the reftable ref backend, the one whose ref transaction — the task branch and its
 * register moved together — is one write on disk. The factory does not create the clone, so it
 * refuses any other backend where a run first touches the clone, with an operator reason naming
 * the remedy, before it writes anything. Real git on both backends: the backend is the subject.
 * Design D19 of add-sandbox-base-image: the check logs nothing; the layer that decides on the
 * refusal writes the one line.
 */
class FactoryCloneRefFormatSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    private final GitProcessRunner runner = new GitProcessRunner()

    private LogCaptureSupport logs

    def setup() {
        logs = LogCaptureSupport.attach(FactoryCloneRefFormat, Level.INFO)
    }

    def cleanup() {
        logs.detach()
    }

    def "F122: a clone the test build creates is on reftable and passes without a log line"() {
        given: 'a clone as every fixture makes one, under the build\'s git configuration'
        Path origin = initBareRepo(tempDir)
        Path work = initTaskWorkingRepo(tempDir)
        assert gitExitCode(work, 'push', origin.toString(), 'HEAD:refs/heads/main') == 0
        Path clone = seedClone(tempDir, origin.toString(), tempDir.resolve('clone'), '-q', '--branch', 'main')

        when:
        new FactoryCloneRefFormat(runner).require(clone)

        then:
        noExceptionThrown()
        gitOutput(clone, 'rev-parse', '--show-ref-format') == FactoryCloneRefFormat.REQUIRED
        logs.list.isEmpty()
    }

    def "F135, NFR-S2: a clone on the files backend is refused with the operator's remedy, and the check writes no line"() {
        given: 'a clone the operator made before the factory required reftable'
        Path clone = filesClone()

        when:
        new FactoryCloneRefFormat(runner).require(clone)

        then: 'the refusal names the clone, what git reported and the re-clone remedy'
        def refused = thrown(CloneRefFormatRefusedException)
        refused.message.contains(clone.toString())
        refused.message.contains('printed: files')
        refused.message.contains("Re-clone the project from origin with git's clone option `--ref-format=reftable`")
        refused.message.contains('gnomish project add')

        and: 'it warns that a fresh clone holds only what origin has, and names the in-place conversion git offers'
        refused.message.contains('push any branch that exists only in this clone first')
        refused.message.contains('`git refs migrate --ref-format=reftable`')
        refused.message.contains('a clone with no worktrees')

        and: 'D19: the refusal is the message; the command, the startup or the condition writes the line'
        logs.list.isEmpty()
    }

    def "F135: a directory git cannot read as a repository is refused, quoting git's own complaint"() {
        given:
        Path notARepo = Files.createDirectories(tempDir.resolve('plain'))

        when:
        new FactoryCloneRefFormat(runner).require(notARepo)

        then:
        def refused = thrown(CloneRefFormatRefusedException)
        refused.message.contains('not a git repository')
        logs.list.isEmpty()
    }

    def "F135: the ref-backend check refuses a files-backend clone and writes nothing to it"() {
        given:
        Path clone = filesClone()
        byte[] configBefore = Files.readAllBytes(clone.resolve('.git/config'))
        def branches = TaskGitFixture.realClaimless().branches()

        when:
        branches.requireRefFormat(clone)

        then: 'the refusal, and the clone\'s configuration untouched: a refused clone is not written to'
        thrown(CloneRefFormatRefusedException)
        Files.readAllBytes(clone.resolve('.git/config')) == configBefore
    }

    // FR6, D4 of own-git-invocation-policy: the check is all that is left of the former hardening
    // step; the factory writes no repository configuration, so no hooks path is persisted.
    def "F122: a reftable clone passes the ref-backend check and no hooks path is written"() {
        given:
        Path clone = initTaskWorkingRepo(tempDir)
        byte[] configBefore = Files.readAllBytes(clone.resolve('.git/config'))
        String hooksPathBefore = gitOutput(clone, 'config', '--local', '--get', 'core.hooksPath')
        def branches = TaskGitFixture.realClaimless().branches()

        when:
        branches.requireRefFormat(clone)

        then: 'the clone\'s own hooks path (the fixture\'s planted one) is left as it was, and nothing else is written'
        noExceptionThrown()
        gitOutput(clone, 'config', '--local', '--get', 'core.hooksPath') == hooksPathBefore
        Files.readAllBytes(clone.resolve('.git/config')) == configBefore
    }

    def "F135: a status read of a files-backend clone is refused before the touchpoint fetch writes a ref"() {
        given: 'origin carries the task branch and its register; the clone is on the files backend'
        Path clone = filesCloneOfOriginWithTask()
        def branches = TaskGitFixture.realClaimless().branches()

        when: 'status reads the task, which no run path checked first'
        branches.readState(clone, 'PROJ-1')

        then: 'the refusal, and neither the tracking ref nor the register was written'
        thrown(CloneRefFormatRefusedException)
        gitExitCode(clone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/gnomish/PROJ-1') != 0
        gitExitCode(clone, 'rev-parse', '--verify', '--quiet', FactoryRegisterRefs.of('PROJ-1').name()) != 0
    }

    def "F135: routing a take over a files-backend clone is refused before the touchpoint fetch writes a ref"() {
        given:
        Path clone = filesCloneOfOriginWithTask()
        def branches = TaskGitFixture.realClaimless().branches()

        when: 'the take router classifies the branch before any run path checks the clone'
        branches.classifyShape(clone, 'PROJ-1')

        then:
        thrown(CloneRefFormatRefusedException)
        gitExitCode(clone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/gnomish/PROJ-1') != 0
        gitExitCode(clone, 'rev-parse', '--verify', '--quiet', FactoryRegisterRefs.of('PROJ-1').name()) != 0
    }

    def "F135: a replica reconcile on a files-backend clone is refused before the touchpoint fetch writes a ref"() {
        given: 'a local task branch, so the container resume reconciles the pair and fetches beside it'
        Path clone = filesCloneOfOriginWithTask()
        assert gitExitCode(clone, 'update-ref', 'refs/heads/gnomish/PROJ-1', gitOutput(clone, 'rev-parse', 'HEAD')) == 0
        def branches = TaskGitFixture.realClaimless().branches()

        when:
        branches.ensureLocalTaskBranch(clone, 'PROJ-1')

        then:
        thrown(CloneRefFormatRefusedException)
        gitExitCode(clone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/gnomish/PROJ-1') != 0
        gitExitCode(clone, 'rev-parse', '--verify', '--quiet', FactoryRegisterRefs.of('PROJ-1').trackingName()) != 0
    }

    /** A files-backend clone of an origin that holds the task branch and its factory register. */
    private Path filesCloneOfOriginWithTask() {
        Path origin = initBareRepo(tempDir)
        Path work = initTaskWorkingRepo(tempDir)
        String tip = gitOutput(work, 'rev-parse', 'HEAD')
        assert gitExitCode(work, 'push', origin.toString(),
        "${tip}:refs/heads/gnomish/PROJ-1", "${tip}:${FactoryRegisterRefs.of('PROJ-1').name()}") == 0
        Path clone = filesClone()
        commit(clone, 'local.txt', 'local')
        addRemote(clone, 'origin', origin.toString())
        clone
    }

    /** A working clone forced onto the files backend, as `git clone` made one before this change. */
    private Path filesClone() {
        Path clone = tempDir.resolve('files-clone')
        assert gitExitCode(tempDir, 'init', '-q', '--ref-format=files', clone.toString()) == 0
        assert gitOutput(clone, 'rev-parse', '--show-ref-format') == 'files'
        clone
    }
}
