package com.github.oinsio.gnomish.testfixtures.standin

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The {@code verdict-scripted} preset of the executor table (task 5.3 of
 * migrate-verify-to-executor-contract): each start or poll answers the next line of the per-run
 * script the spec wrote and records what it saw — the operation, the key, {@code HEAD} and the working
 * copy — beside the link, never in the working copy, so a spec drives a pollable verdict program whose
 * run outlives every box it runs in. Lives beside {@link StandInLibrarySpec}, whose per-preset features
 * cover this preset's rows.
 */
class StandInScriptedVerdictSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def "FR11: each operation answers the script's next line and records its operation, key, HEAD and working copy"() {
        given:
        Path repo = initWorkingRepo(tempDir, 'work')
        Files.writeString(repo.resolve('a.txt'), 'a\n')
        commitAll(repo)
        String head = gitOutput(repo, 'rev-parse', 'HEAD')
        Path link = StandIn.recording(Files.createDirectories(tempDir.resolve('bin')), 'verdict-scripted')
        Files.writeString(StandIn.beside(link, 'script'), 'first\nsecond\n')

        when:
        def started = run(link, repo, 'start', '')
        def polled = run(link, repo, 'poll', 'k-1')

        then:
        [started, polled] == ['first\n', 'second\n']
        StandIn.beside(link, 'heads').toFile().readLines() == [
            "start - ${head} ${repo.toRealPath()}".toString(),
            "poll k-1 ${head} ${repo.toRealPath()}".toString()
        ]
        StandIn.beside(link, 'script').toFile().text == ''
        gitOutput(repo, 'status', '--porcelain') == ''
    }

    private String run(Path link, Path repo, String operation, String key) {
        Path resultFile = tempDir.resolve("result-${operation}.json")
        def builder = new ProcessBuilder([link.toString(), operation]).directory(repo.toFile())
        TestChildEnvironment.cleared(builder).putAll([GNOMISH_RESULT_FILE: resultFile.toString(), GNOMISH_PENDING_KEY: key])
        builder.redirectErrorStream(true)
        Process process = builder.start()
        String output = process.inputStream.text
        assert process.waitFor() == 0: output
        resultFile.toFile().text
    }
}
