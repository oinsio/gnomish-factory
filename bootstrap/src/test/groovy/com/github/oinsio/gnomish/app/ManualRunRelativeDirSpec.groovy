package com.github.oinsio.gnomish.app

import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7, FR13 of fix-operator-blockers (cli-arguments scenario "Relative directory starts git
 * mode"): {@code gnomish run} given a {@code --dir} relative to the JVM's working directory starts
 * git mode exactly as with the clone's absolute path. Before the argument owner resolved {@code
 * --dir}, the relative path reached the git-objects library, which runs git with the git dir as its
 * working directory and also passes it as {@code --git-dir}; {@code HEAD} then peeled to nothing
 * and the run refused with "not a git repository" (proposal Q2). Kept beside {@link
 * ManualRunRunnerSpec}, whose host git-mode wiring it reuses, rather than in it — that file is past
 * the size cap.
 */
class ManualRunRelativeDirSpec extends Specification implements AppAssemblyFixture, ManualRunPipelineFixture {

    @TempDir
    Path projectRoot

    @TempDir
    Path homeDir

    def "FR7: run with a --dir relative to the working directory starts git mode"() {
        given:
        makeProjectRootAGitClone(projectRoot)
        writeOneStagePipeline(projectRoot)
        Path relative = Path.of('').toAbsolutePath().relativize(projectRoot)
        assert !relative.isAbsolute()
        def originalIn = System.in
        def originalOut = System.out
        System.in = new ByteArrayInputStream((System.lineSeparator()).getBytes('UTF-8'))
        def capturedOut = new ByteArrayOutputStream()
        System.out = new PrintStream(capturedOut, true, 'UTF-8')
        def runner = newManualRunRunner(projectRoot, homeDir)
        def args = new DefaultApplicationArguments(
                "--dir=${relative}".toString(),
                '--task=do the thing',
                '--task-id=manual-relative-dir',
                '--interactive')

        when:
        runner.run(args)

        then: 'the task branch exists in the clone, and the banner names it'
        noExceptionThrown()
        gitOutput(projectRoot, 'rev-parse', '--verify', 'refs/heads/gnomish/manual-relative-dir').trim()
        capturedOut.toString('UTF-8').readLines().any {
            it.contains('git mode: branch') && it.contains('gnomish/manual-relative-dir')
        }

        cleanup:
        System.in = originalIn
        System.out = originalOut
    }
}
