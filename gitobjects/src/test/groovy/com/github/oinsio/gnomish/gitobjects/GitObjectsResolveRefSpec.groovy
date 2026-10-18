package com.github.oinsio.gnomish.gitobjects

import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR17 of fix-terminal-receipt-convergence (design D15 as amended 2026-10-17), the second layer:
 * {@link GitObjects#resolveRef} refuses a {@code rev-parse} that exited zero without printing one
 * full object id with the library's own {@link GitObjectsException} — the type its callers classify
 * by — and keeps "no such ref" for a non-zero exit. Driven on a {@link FakeProcess} (design D16).
 */
class GitObjectsResolveRefSpec extends Specification {

    @TempDir
    Path tempDir

    private GitObjects answering(int exit, String stdout) {
        ProcessLaunch launch = { ProcessBuilder builder, StdinSource stdin ->
            FakeProcess fake = FakeProcess.running()
            fake.writeStdout(stdout)
            fake.exit(exit)
            fake
        } as ProcessLaunch
        new GitObjects(new GitExec(tempDir, 'git', new ProcessSupervisor(), launch), tempDir)
    }

    // Scenario: lifecycle/subprocess-supervision / A value reader asserts its frame
    def "FR17: a rev-parse that exits zero without one full object id is refused: #answer"() {
        when:
        answering(0, answer).resolveRef('refs/heads/main')

        then:
        GitObjectsException e = thrown()
        e.message == 'git rev-parse exited 0 without printing a full object id'

        where:
        answer << [
            'a' * 39 + '\n',
            'a' * 41 + '\n',
            'not-an-id\n',
            ''
        ]
    }

    def "FR17: a non-zero exit is still no such ref, and a full id is the commit"() {
        expect:
        answering(1, '').resolveRef('refs/heads/main').isEmpty()
        answering(0, 'a' * 40 + '\n').resolveRef('refs/heads/main') == Optional.of(new ObjectId('a' * 40))
    }
}
