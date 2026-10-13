package com.github.oinsio.gnomish.adapter.git

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.TaskGitFixture
import com.github.oinsio.gnomish.app.port.git.CloneRefFormatRefusedException
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
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

    def "F135, NFR-S2: a clone on the files backend is refused with the operator's remedy and one coded ERROR"() {
        given: 'a clone the operator made before the factory required reftable'
        Path clone = filesClone()

        when:
        new FactoryCloneRefFormat(runner).require(clone)

        then: 'the refusal names the clone, what git reported and the re-clone remedy'
        def refused = thrown(CloneRefFormatRefusedException)
        refused.message.contains(clone.toString())
        refused.message.contains('printed: files')
        refused.message.contains('clone --ref-format=reftable <origin-url> <dir>')
        refused.message.contains('gnomish project add')

        and: 'one ERROR carries the catalog code, the clone and the reported backend'
        logs.list.size() == 1
        logs.list[0].level == Level.ERROR
        logs.list[0].formattedMessage.startsWith(OperatorEvent.FACTORY_CLONE_REF_FORMAT_REFUSED.head())
        logs.list[0].formattedMessage.contains(clone.toString())
        logs.list[0].formattedMessage.contains('reported: files')
    }

    def "F135: a directory git cannot read as a repository is refused, quoting git's own complaint"() {
        given:
        Path notARepo = Files.createDirectories(tempDir.resolve('plain'))

        when:
        new FactoryCloneRefFormat(runner).require(notARepo)

        then:
        def refused = thrown(CloneRefFormatRefusedException)
        refused.message.contains('not a git repository')
        logs.list*.level == [Level.ERROR]
    }

    def "F135: hardening a files-backend clone refuses it before writing its configuration"() {
        given:
        Path clone = filesClone()
        def branches = TaskGitFixture.realClaimless().branches()

        when:
        branches.harden(clone)

        then: 'the refusal, and no hooks path written: a refused clone is not touched'
        thrown(CloneRefFormatRefusedException)
        gitExitCode(clone, 'config', 'core.hooksPath') != 0
    }

    def "F122: hardening a reftable clone still points its hooks at the empty directory"() {
        given:
        Path clone = initTaskWorkingRepo(tempDir)
        def branches = TaskGitFixture.realClaimless().branches()

        when:
        branches.harden(clone)

        then:
        gitOutput(clone, 'config', 'core.hooksPath').endsWith(FactoryCloneHardening.EMPTY_HOOKS_DIR)
    }

    /** A working clone forced onto the files backend, as `git clone` made one before this change. */
    private Path filesClone() {
        Path clone = tempDir.resolve('files-clone')
        assert gitExitCode(tempDir, 'init', '-q', '--ref-format=files', clone.toString()) == 0
        assert gitOutput(clone, 'rev-parse', '--show-ref-format') == 'files'
        clone
    }
}
