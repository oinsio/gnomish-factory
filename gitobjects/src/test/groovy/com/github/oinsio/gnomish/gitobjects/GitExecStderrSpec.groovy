package com.github.oinsio.gnomish.gitobjects

import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR25 of add-sandbox-core: {@link GitExec} captures a failing git command's stderr as diagnostic
 * context — the stderr drain must actually run and deliver the bytes into the
 * result, not merely be constructed.
 */
class GitExecStderrSpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    // FR25: stderr of a failed plumbing command reaches Result.stderr through the drain
    def "FR25: a failing git command's stderr is captured into the result"() {
        given: 'a real bare repository and a plumbing command that must fail loudly'
        Path bare = seedBareRepo(tempDir, ['file.txt': 'content'])
        def exec = new GitExec(bare, 'git', new ProcessSupervisor(), ProcessLaunch.grouped())

        when:
        def result = exec.run([
            'cat-file',
            '-p',
            'no-such-object'
        ])

        then: 'the failure is visible and the stderr diagnostic names the missing object'
        result.exitCode() != 0
        result.stderr().contains('no-such-object')
        result.stdout().length == 0
    }
}
